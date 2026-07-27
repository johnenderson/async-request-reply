package com.async.request.reply.spi;

/**
 * SPI síncrono: a rotina roda inteira dentro de {@link #handle()} e o retorno do
 * método é o sinal de conclusão — a lib marca o job como COMPLETED, ou FAILED se
 * a rotina lançar exceção.
 *
 * <p>Não devolve valor: a biblioteca controla execução, não serve dados
 * (ADR 0004). O efeito da rotina é a escrita na base do próprio projeto
 * consumidor, e o cliente lê o resultado pelo endpoint de domínio dele — com o
 * SQL e os filtros dele — depois de saber que o job terminou.</p>
 *
 * <p>Use {@link AsyncJobHandler} quando a rotina apenas dispara o trabalho e a
 * conclusão chega depois (evento/webhook), reportada via {@link JobReporter}.</p>
 */
public interface JobHandler extends Routine {

    void handle();
}
