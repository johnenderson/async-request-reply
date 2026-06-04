package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.exception.InvalidJobRequestException;
import com.async.request.reply.core.port.in.SubmitJobPortIn;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import com.async.request.reply.core.result.SubmittedJob;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Implementação do {@link SubmitJobPortIn}.
 * Responsável pela request validation, single-flight (coalescing automático)
 * e pela decisão de enfileirar o processamento.
 */
@Service
public class SubmitJobUseCase implements SubmitJobPortIn {

    private final JobRepositoryPortOut repository;
    private final JobProcessorPortOut processor;
    private final JobPolicyPortOut policy;
    private final JobSubmissionPolicyPortOut submissionPolicy;
    private final SingleFlightPortOut singleFlight;
    private final CoalescingKeyPortOut coalescingKey;

    public SubmitJobUseCase(JobRepositoryPortOut repository,
                            JobProcessorPortOut processor,
                            JobPolicyPortOut policy,
                            JobSubmissionPolicyPortOut submissionPolicy,
                            SingleFlightPortOut singleFlight,
                            CoalescingKeyPortOut coalescingKey) {
        this.repository = repository;
        this.processor = processor;
        this.policy = policy;
        this.submissionPolicy = submissionPolicy;
        this.singleFlight = singleFlight;
        this.coalescingKey = coalescingKey;
    }

    @Override
    public SubmittedJob execute(String type, Object payload, String idempotencyKey) {
        ensureRoutineRegistered(type);

        // 1. Idempotency: um retry da mesma key retorna sempre o MESMO job
        if (idempotencyKey != null) {
            Optional<Job> existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return submitted(existing.get().getId());
            }
        }

        // 2. Single-flight (coalescing): se já há um job ativo p/ a chave, retorna ele
        String key = submissionPolicy.coalesceInFlight() ? coalescingKey.keyFor(type, payload) : null;
        if (key != null) {
            Optional<String> owner = singleFlight.peek(key).filter(this::isActive);
            if (owner.isPresent()) {
                return submitted(owner.get());
            }
        }

        // 3. Reivindica o single-flight ANTES de persistir (evita job órfão)
        String id = UUID.randomUUID().toString();
        if (key != null) {
            String winner = singleFlight.begin(key, id);
            if (!winner.equals(id)) {
                if (isActive(winner)) {
                    return submitted(winner);            // perdeu a corrida → dedupe (nada persistido)
                }
                singleFlight.takeOver(key, id);          // guard obsoleto → assume
            }
        }

        // 4. Cria (com dedupe de idempotência) e dispara o processamento
        Job job = repository.create(id, type, payload, idempotencyKey);
        if (job.getId().equals(id) && job.getStatus() == JobStatus.PENDING) {
            processor.process(job); // via Spring proxy → @Async funciona
        }
        return submitted(job.getId());
    }

    /**
     * Um job é "ativo" enquanto não atingiu estado terminal.
     */
    private boolean isActive(String jobId) {
        return repository.findById(jobId)
                .map(j -> j.getStatus() == JobStatus.PENDING || j.getStatus() == JobStatus.PROCESSING)
                .orElse(false);
    }

    private SubmittedJob submitted(String jobId) {
        return new SubmittedJob(jobId, policy.retryAfterSeconds());
    }

    private void ensureRoutineRegistered(String type) {
        if (!processor.supports(type)) {
            throw new InvalidJobRequestException("No routine registered for type '" + type + "'.");
        }
    }
}
