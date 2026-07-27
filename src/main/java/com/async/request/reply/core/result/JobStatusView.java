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

    /**
     * COMPLETED: estado terminal. Não redireciona — a lib não serve dados
     * (ADR 0004); o cliente lê o resultado pelo endpoint de domínio dele depois
     * de saber que terminou.
     */
    record Completed(JobStatusSnapshot body, Instant expiresAt) implements JobStatusView {}

    /** FAILED: erro terminal; o adapter web o renderiza como Problem Detail. */
    record Failed(JobFailure error) implements JobStatusView {}

    /**
     * CANCELLED: estado terminal, mas o recurso de status segue legível — o
     * padrão trata "cancelado" como um valor de status, não como recurso que
     * deixou de existir.
     */
    record Cancelled(JobStatusSnapshot body, Instant expiresAt) implements JobStatusView {}

    /** Nenhum job existe para o id informado. */
    record NotFound() implements JobStatusView {}
}
