package com.async.request.reply.core.port.out;

import com.async.request.reply.core.domain.Job;

import java.util.Optional;

/**
 * Outbound port para persistência de jobs. As transições são <b>atômicas</b>
 * (check-and-set no store): retornam {@code true} se aplicadas, {@code false}
 * se o job já estava em estado terminal — assim concorrência entre instâncias
 * não sobrescreve cancelamentos/conclusões.
 */
public interface JobRepositoryPortOut {

    /**
     * Cria um novo job com id pré-gerado. Se {@code idempotencyKey} já existir,
     * retorna o job existente (sem criar outro) — dedupe atômico.
     */
    Job create(String id, String type, Object payload, String idempotencyKey);

    Optional<Job> findById(String id);

    /** Job já associado a uma idempotencyKey, se houver. */
    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    /** PENDING → PROCESSING. */
    boolean start(String id);

    /** PENDING/PROCESSING → COMPLETED, gravando o resultado. */
    boolean complete(String id, Object result);

    /** PENDING/PROCESSING → FAILED, gravando o erro. */
    boolean fail(String id, String title, String detail);

    /** PENDING/PROCESSING → CANCELLED. */
    boolean cancel(String id);

    /** Atualiza o progresso (0–100) somente enquanto o job está ativo. */
    boolean progress(String id, int percent);
}
