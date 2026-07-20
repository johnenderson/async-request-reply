package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.exception.IdempotencyKeyConflictException;
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
    public SubmittedJob execute(String type, String idempotencyKey) {
        ensureRoutineRegistered(type);

        // 1. Idempotency: um retry da mesma key retorna sempre o MESMO job
        if (idempotencyKey != null) {
            Optional<Job> existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                ensureSameType(existing.get(), type);
                return submitted(existing.get().getId());
            }
        }

        // 2. Sem coalescing: cria direto
        String key = submissionPolicy.coalesceInFlight() ? coalescingKey.keyFor(type) : null;
        if (key == null) {
            return submitted(createAndDispatch(type, idempotencyKey, null).getId());
        }

        // 3. Coalescing: peek → create → claim serializados pelo lock da chave.
        //    O claim só acontece DEPOIS de persistir, então o guard nunca aponta
        //    para um job inexistente (elimina o take-over indevido em corrida).
        return singleFlight.withLock(key, () -> {
            Optional<String> owner = singleFlight.peek(key).filter(this::isActive);
            if (owner.isPresent()) {
                return submitted(owner.get());
            }
            return submitted(createAndDispatch(type, idempotencyKey, key).getId());
        });
    }

    /**
     * Cria o job (com dedupe de idempotência), reivindica o single-flight quando
     * aplicável e dispara o processamento. O claim precede o dispatch para que a
     * liberação do guard (na transição terminal) nunca corra antes do claim.
     */
    private Job createAndDispatch(String type, String idempotencyKey, String singleFlightKey) {
        String id = UUID.randomUUID().toString();
        Job job = repository.create(id, type, idempotencyKey);
        boolean fresh = job.getId().equals(id);
        if (!fresh) {
            ensureSameType(job, type); // dedupe de idempotência venceu a corrida
        }
        if (fresh && singleFlightKey != null) {
            singleFlight.claim(singleFlightKey, id);
        }
        if (fresh && job.getStatus() == JobStatus.PENDING) {
            processor.process(job); // via Spring proxy → @Async funciona
        }
        return job;
    }

    /**
     * Idempotency-Key só pode ser reusada para retries da MESMA operação:
     * a mesma key com outro {@code type} devolveria um job do tipo errado.
     */
    private static void ensureSameType(Job existing, String requestedType) {
        if (!existing.getType().equals(requestedType)) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency-Key já usada para o type '" + existing.getType()
                            + "'; não pode ser reusada para o type '" + requestedType + "'.");
        }
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
