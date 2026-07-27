package com.async.request.reply.spi;

/**
 * API exposta pela lib para o projeto consumidor reportar andamento e conclusão
 * de um job, a partir do {@code jobId}. Injete este bean e chame de onde quiser
 * (worker, listener de fila, webhook).
 *
 * <p>Não há como anexar resultado: a lib controla execução, não serve dados
 * (ADR 0004). A rotina escreve na base do consumidor e apenas avisa que
 * terminou.</p>
 *
 * <p>Todos os métodos retornam se o report foi <b>aceito</b>: {@code false}
 * significa que o job não existe mais ou já atingiu estado terminal —
 * tipicamente porque foi cancelado. Um worker que recebe {@code false} deve
 * parar o trabalho em vez de seguir reportando.</p>
 */
public interface JobReporter {

    /**
     * Atualiza o progresso (0–100) de um job em andamento.
     *
     * <p>Também é o <b>heartbeat</b> da rotina: renova o prazo de
     * {@code async-jobs.recovery.processing-timeout}. Rotinas longas devem
     * chamar periodicamente, ou serão declaradas zumbis e marcadas como falhas
     * enquanto ainda executam.</p>
     */
    boolean progress(String jobId, int percent);

    /** Marca o job como concluído. */
    boolean complete(String jobId);

    /** Marca o job como falho, com título e detalhe do erro. */
    boolean fail(String jobId, String title, String detail);
}
