package com.async.request.reply.spi;

/**
 * SPI síncrono: a rotina roda inteira dentro de {@link #handle(Object)} e o
 * retorno É o sinal de conclusão — a lib marca o job como COMPLETED com o
 * resultado retornado (ou FAILED se lançar exceção).
 *
 * <p>Use {@link AsyncJobHandler} quando a rotina apenas dispara o trabalho e o
 * resultado chega depois (evento/webhook), reportado via {@link JobReporter}.
 *
 * @param <P> tipo do input (deserializado do payload JSON)
 * @param <R> tipo do resultado produzido pela rotina
 */
public interface JobHandler<P, R> extends Routine {

    R handle(P input);
}
