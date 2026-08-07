package com.async.request.reply.spi;

/**
 * Contexto entregue à rotina em execução. Expõe o {@code jobId} gerado pela lib
 * — para o projeto consumidor amarrá-lo ao seu worker e reportar depois via
 * {@link JobReporter} — e o sinal de cancelamento.
 */
public interface JobContext {

    String jobId();

    /**
     * Se o job foi cancelado enquanto a rotina roda. É a base do
     * <b>cancelamento cooperativo</b>: a lib não interrompe a thread da rotina
     * (interromper trabalho a meio caminho deixaria a base do consumidor em
     * estado parcial, sem ninguém para consertar), então quem decide parar é a
     * própria rotina — tipicamente entre lotes:
     *
     * <pre>{@code
     * public void handle(JobContext ctx) {
     *     for (var lote : contas.emLotes(500)) {
     *         if (ctx.isCancelled()) {
     *             return;          // para num ponto consistente
     *         }
     *         contas.reavaliar(lote);
     *     }
     * }
     * }</pre>
     *
     * <p><b>Cada chamada lê o storage</b>, então pergunte entre lotes, não a cada
     * item. Rotinas que já chamam {@link JobReporter#progress} periodicamente não
     * precisam disto: o {@code false} devolvido por {@code progress} carrega a
     * mesma informação, sem leitura extra.</p>
     *
     * <p>Parar não muda o estado do job: ele permanece {@code CANCELLED}. Uma
     * rotina que retorna normalmente após parar não corre risco de "descancelar"
     * o job — a transição para {@code COMPLETED} é recusada em estado terminal.</p>
     *
     * @return {@code false} também quando o job não é encontrado — não há o que
     *         parar por conta de um job que não existe mais
     */
    boolean isCancelled();
}
