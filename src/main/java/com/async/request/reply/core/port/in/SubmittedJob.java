package com.async.request.reply.core.port.in;

/**
 * Output do input port de submissão.
 */
public record SubmittedJob(String jobId, int retryAfterSeconds) {}
