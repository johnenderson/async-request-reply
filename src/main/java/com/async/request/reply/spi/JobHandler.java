package com.async.request.reply.spi;

/**
 * SPI síncrono: a rotina roda inteira dentro de {@link #handle()} e o
 * retorno É o sinal de conclusão — a lib marca o job como COMPLETED com o
 * resultado retornado (ou FAILED se lançar exceção).
 *
 * <p>Use {@link AsyncJobHandler} quando a rotina apenas dispara o trabalho e o
 * resultado chega depois (evento/webhook), reportado via {@link JobReporter}.
 *
 * @param <R> tipo do resultado produzido pela rotina
 */
public interface JobHandler<R> extends Routine {

    R handle();
}
