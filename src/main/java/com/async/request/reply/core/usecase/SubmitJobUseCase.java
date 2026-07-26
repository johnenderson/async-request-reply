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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * Implementação do {@link SubmitJobPortIn}.
 * Responsável pela request validation, single-flight (coalescing automático)
 * e pela decisão de enfileirar o processamento.
 */
public class SubmitJobUseCase implements SubmitJobPortIn {

    private static final Logger log = LoggerFactory.getLogger(SubmitJobUseCase.class);

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

        String key = submissionPolicy.coalesceInFlight() ? coalescingKey.keyFor(type) : null;

        // 2. Decide e persiste. Com coalescing, peek → create → claim rodam sob o
        //    lock da chave, e o claim só ocorre depois de persistir (o guard nunca
        //    aponta para job inexistente).
        Submission submission = (key == null)
                ? create(type, idempotencyKey, null)
                : singleFlight.withLock(key, () -> {
                    Optional<String> owner = singleFlight.peek(key).filter(this::isActive);
                    return owner.isPresent()
                            ? Submission.existing(owner.get())
                            : create(type, idempotencyKey, key);
                });

        // 3. Dispatch FORA do lock: enfileirar pode bloquear esperando vaga no
        //    executor, e segurar o lock nesse intervalo travaria todos os submits
        //    do mesmo type — em todas as instâncias.
        dispatch(submission);
        return submitted(submission.jobId());
    }

    /**
     * Resultado da decisão de submissão: o id a devolver ao cliente e, quando o
     * job é novo, o próprio job a ser enfileirado.
     */
    private record Submission(String jobId, Job toDispatch) {

        static Submission existing(String jobId) {
            return new Submission(jobId, null);
        }
    }

    private Submission create(String type, String idempotencyKey, String singleFlightKey) {
        String id = UUID.randomUUID().toString();
        Job job = repository.create(id, type, idempotencyKey, singleFlightKey);
        if (!job.getId().equals(id)) {
            // o dedupe de idempotência venceu a corrida: o job já existe e já foi
            // (ou será) despachado por quem o criou
            ensureSameType(job, type);
            return Submission.existing(job.getId());
        }
        if (singleFlightKey != null) {
            singleFlight.claim(singleFlightKey, id);
        }
        return job.getStatus() == JobStatus.PENDING
                ? new Submission(id, job)
                : Submission.existing(id);
    }

    /**
     * Enfileira o processamento. Uma recusa do executor não invalida a submissão:
     * o job está persistido como PENDING e a varredura de recuperação o
     * reenfileira — melhor devolver `202` do que `500` para um job aceito.
     */
    private void dispatch(Submission submission) {
        if (submission.toDispatch() == null) {
            return;
        }
        try {
            processor.process(submission.toDispatch()); // via Spring proxy → @Async funciona
        } catch (RuntimeException e) {
            log.warn("Dispatch do job '{}' recusado pelo executor; a recuperacao reenfileira: {}",
                    submission.jobId(), e.getMessage());
        }
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
