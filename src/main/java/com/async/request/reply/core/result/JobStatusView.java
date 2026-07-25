package com.async.request.reply.core.result;

import com.async.request.reply.core.domain.JobFailure;

import java.time.Instant;

/**
 * Domain outcome da consulta do status de um job. O use case decide qual
 * variante se aplica; o adapter web mapeia cada variante para uma HTTP response.
 */
public sealed interface JobStatusView {

    /** PENDING / PROCESSING: o client deve continuar o polling. */
    record InProgress(JobStatusSnapshot body, int retryAfterSeconds, Instant expiresAt) implements JobStatusView {}

    /** COMPLETED: redireciona o client para o result resource. */
    record Completed(Instant expiresAt) implements JobStatusView {}

    /** FAILED: erro terminal; o adapter web o renderiza como Problem Detail. */
    record Failed(JobFailure error) implements JobStatusView {}

    /** CANCELLED: o job foi cancelado. */
    record Cancelled() implements JobStatusView {}

    /** Nenhum job existe para o id informado. */
    record NotFound() implements JobStatusView {}
}
