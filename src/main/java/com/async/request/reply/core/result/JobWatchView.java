package com.async.request.reply.core.result;

/**
 * Domain outcome do acompanhamento em tempo real de um job.
 */
public sealed interface JobWatchView {

    /** Assinatura ativa; o caller fecha {@code subscription} ao terminar. */
    record Watching(AutoCloseable subscription) implements JobWatchView {}

    /** Nenhum job existe para o id informado. */
    record NotFound() implements JobWatchView {}
}
