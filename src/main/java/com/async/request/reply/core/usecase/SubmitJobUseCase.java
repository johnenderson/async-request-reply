package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.exception.IdempotencyKeyConflictException;
import com.async.request.reply.core.exception.InvalidJobRequestException;
import com.async.request.reply.core.port.in.SubmitJobPortIn;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobFreshnessPolicyPortOut;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;
import com.async.request.reply.core.result.SubmittedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementação do {@link SubmitJobPortIn}.
 *
 * <p>O {@code POST} significa "garanta que este trabalho esteja feito", e não
 * "rode agora": a submissão é idempotente no tempo. Três mecanismos decidem se
 * uma carga nova é necessária, em ordem:</p>
 *
 * <ol>
 *   <li><b>Idempotência</b>: um retry da mesma key devolve sempre o mesmo job;</li>
 *   <li><b>Frescor</b>: se uma carga do mesmo escopo concluiu dentro da janela, os
 *       dados seguem quentes e devolvemos aquele job — a menos que o cliente
 *       force com {@code Cache-Control: no-cache};</li>
 *   <li><b>Single-flight</b>: se já existe job ativo para o escopo, devolvemos
 *       ele. Isso é resolvido <b>dentro</b> do {@code create}, por índice único
 *       parcial — sem lock distribuído (ADR 0004).</li>
 * </ol>
 */
public class SubmitJobUseCase implements SubmitJobPortIn {

    private static final Logger log = LoggerFactory.getLogger(SubmitJobUseCase.class);

    private final JobRepositoryPortOut repository;
    private final JobProcessorPortOut processor;
    private final JobPolicyPortOut policy;
    private final JobSubmissionPolicyPortOut submissionPolicy;
    private final JobFreshnessPolicyPortOut freshnessPolicy;
    private final CoalescingKeyPortOut coalescingKey;
    private final Clock clock;

    public SubmitJobUseCase(JobRepositoryPortOut repository,
                            JobProcessorPortOut processor,
                            JobPolicyPortOut policy,
                            JobSubmissionPolicyPortOut submissionPolicy,
                            JobFreshnessPolicyPortOut freshnessPolicy,
                            CoalescingKeyPortOut coalescingKey,
                            Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.policy = policy;
        this.submissionPolicy = submissionPolicy;
        this.freshnessPolicy = freshnessPolicy;
        this.coalescingKey = coalescingKey;
        this.clock = clock;
    }

    @Override
    public SubmittedJob execute(String type, String idempotencyKey) {
        return execute(type, idempotencyKey, false);
    }

    @Override
    public SubmittedJob execute(String type, String idempotencyKey, boolean forceRefresh) {
        ensureRoutineRegistered(type);

        // 1. Idempotência: um retry da mesma key retorna sempre o MESMO job
        if (idempotencyKey != null) {
            Optional<Job> existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                ensureSameType(existing.get(), type);
                return submitted(existing.get().getId());
            }
        }

        String scope = submissionPolicy.coalesceInFlight() ? coalescingKey.keyFor(type) : null;

        // 2. Frescor: dado ainda quente dispensa carga nova
        if (!forceRefresh) {
            Optional<Job> fresh = freshJob(type, scope);
            if (fresh.isPresent()) {
                log.debug("Job '{}' concluido recentemente para o escopo '{}'; carga desnecessaria",
                        fresh.get().getId(), scope);
                return submitted(fresh.get().getId());
            }
        }

        // 3. Cria; o coalescing e resolvido pelo indice unico dentro do create
        String id = UUID.randomUUID().toString();
        Job job = repository.create(id, type, idempotencyKey, scope);
        if (!job.getId().equals(id)) {
            // conflito: ja existe job ativo (ou da mesma key) — nada a despachar
            ensureSameType(job, type);
            return submitted(job.getId());
        }

        dispatch(job);
        return submitted(job.getId());
    }

    /** Último job concluído do escopo dentro da janela de frescor, se houver. */
    private Optional<Job> freshJob(String type, String scope) {
        if (scope == null) {
            return Optional.empty();
        }
        return freshnessPolicy.freshnessFor(type)
                .flatMap(window -> repository.findFreshCompleted(scope, clock.instant().minus(window)));
    }

    /**
     * Enfileira o processamento. Uma recusa do executor não invalida a submissão:
     * o job está persistido como PENDING e a varredura de recuperação o
     * reenfileira — melhor devolver {@code 202} do que {@code 500} para um job
     * aceito.
     */
    private void dispatch(Job job) {
        try {
            processor.process(job); // via Spring proxy → @Async funciona
        } catch (RuntimeException e) {
            log.warn("Dispatch do job '{}' recusado pelo executor; a recuperacao reenfileira: {}",
                    job.getId(), e.getMessage());
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

    private SubmittedJob submitted(String jobId) {
        return new SubmittedJob(jobId, policy.retryAfterSeconds());
    }

    private void ensureRoutineRegistered(String type) {
        if (!processor.supports(type)) {
            throw new InvalidJobRequestException("No routine registered for type '" + type + "'.");
        }
    }
}
