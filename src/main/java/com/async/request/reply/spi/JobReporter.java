package com.async.request.reply.spi;

import java.util.List;

/**
 * API exposta pela lib para o projeto consumidor reportar andamento, anexar
 * itens ao resultado (em chunks) e concluir um job, a partir do {@code jobId}.
 * Injete este bean e chame de onde quiser (worker, listener de fila, webhook).
 *
 * <p>Todos os métodos retornam se o report foi <b>aceito</b>: {@code false}
 * significa que o job não existe mais (TTL) ou já atingiu estado terminal —
 * tipicamente porque foi cancelado. Um worker que recebe {@code false} deve
 * parar o trabalho em vez de seguir reportando.</p>
 */
public interface JobReporter {

    /** Atualiza o progresso (0–100) de um job em andamento. */
    boolean progress(String jobId, int percent);

    /** Anexa itens ao resultado (append incremental — paginável no storage). */
    boolean append(String jobId, List<?> items);

    /** Marca o job como concluído (os itens já foram anexados via {@link #append}). */
    boolean complete(String jobId);

    /** Conveniência: anexa o resultado de uma vez e conclui. */
    boolean complete(String jobId, Object result);

    /** Marca o job como falho, com título e detalhe do erro. */
    boolean fail(String jobId, String title, String detail);
}
