package com.async.request.reply.adapter.out.policy;

import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.port.out.JobPolicyPortOut;

import java.time.Duration;
import java.time.Instant;

/**
 * Adapter out: implementação default do {@link JobPolicyPortOut}.
 * A política padrão centraliza os hints HTTP usados pelo fluxo assíncrono:
 * {@code Retry-After} orienta o intervalo de polling e {@code Expires} reflete
 * a mesma retenção configurada para o storage.
 */
public class DefaultJobPolicyAdapterOut implements JobPolicyPortOut {

    /** Mesma retenção do storage (async-jobs.retention). */
    private final Duration retention;
    private final int retryAfterSeconds;

    public DefaultJobPolicyAdapterOut(AsyncJobsProperties properties) {
        this.retention = properties.retention();
        this.retryAfterSeconds = properties.retryAfterSeconds();
    }

    @Override
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public Instant expiresAt(Job job) {
        return job.getLastUpdatedAt().plus(retention);
    }
}
