package com.async.request.reply.spi;

/**
 * SPI assíncrono (fire-and-forget): a rotina apenas <b>dispara</b> o trabalho
 * em {@link #start(JobContext)} e retorna. A lib mantém o job em
 * PROCESSING — a conclusão é reportada depois pelo worker do projeto consumidor
 * via {@link JobReporter}, usando o {@code jobId} disponível em {@link JobContext}.
 */
public interface AsyncJobHandler extends Routine {

    void start(JobContext ctx);
}
