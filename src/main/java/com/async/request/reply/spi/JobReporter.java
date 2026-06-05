package com.async.request.reply.spi;

import java.util.List;

/**
 * API exposta pela lib para o projeto consumidor reportar andamento, anexar
 * itens ao resultado (em chunks) e concluir um job, a partir do {@code jobId}.
 * Injete este bean e chame de onde quiser (worker, listener de fila, webhook).
 */
public interface JobReporter {

    /** Atualiza o progresso (0–100) de um job em andamento. */
    void progress(String jobId, int percent);

    /** Anexa itens ao resultado (append incremental — paginável no storage). */
    void append(String jobId, List<?> items);

    /** Marca o job como concluído (os itens já foram anexados via {@link #append}). */
    void complete(String jobId);

    /** Conveniência: anexa o resultado de uma vez e conclui. */
    void complete(String jobId, Object result);

    /** Marca o job como falho, com título e detalhe do erro. */
    void fail(String jobId, String title, String detail);
}
