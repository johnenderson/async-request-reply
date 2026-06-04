package com.async.request.reply.spi;

/**
 * API exposta pela lib para o projeto consumidor reportar o andamento e a
 * conclusão de um job assíncrono, a partir do {@code jobId}. Injete este bean
 * e chame-o de onde quiser (worker, listener de fila, webhook, etc.).
 */
public interface JobReporter {

    /** Atualiza o progresso (0–100) de um job em andamento. */
    void progress(String jobId, int percent);

    /** Marca o job como concluído com o resultado informado. */
    void complete(String jobId, Object result);

    /** Marca o job como falho, com título e detalhe do erro. */
    void fail(String jobId, String title, String detail);
}
