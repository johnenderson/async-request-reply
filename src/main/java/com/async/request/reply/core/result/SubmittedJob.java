package com.async.request.reply.core.result;

/**
 * Resultado do caso de uso de submissão.
 */
public record SubmittedJob(String jobId, int retryAfterSeconds) {}
