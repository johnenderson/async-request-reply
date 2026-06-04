package com.async.request.reply.spi;

/**
 * Contexto entregue a uma {@link AsyncJobHandler}. Expõe o {@code jobId}
 * gerado pela lib para que o projeto consumidor possa amarrá-lo ao seu
 * worker e, mais tarde, reportar o resultado via {@link JobReporter}.
 */
public interface JobContext {

    String jobId();
}
