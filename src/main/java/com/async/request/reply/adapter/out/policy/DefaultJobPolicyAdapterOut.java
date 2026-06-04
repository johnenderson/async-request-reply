package com.async.request.reply.adapter.out.policy;

import com.async.request.reply.autoconfigure.AsyncJobsProperties;
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

    /** Mesma retenção do storage (async-jobs.result-ttl). */
    private final Duration retention;
    private final int retryAfterSeconds;
    private final boolean coalesceInFlight;

    public DefaultJobPolicyAdapterOut(AsyncJobsProperties properties) {
        this.retention = properties.resultTtl();
        this.retryAfterSeconds = properties.retryAfterSeconds();
        this.coalesceInFlight = properties.coalesceInFlight();
    }

    @Override
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public boolean coalesceInFlight() {
        return coalesceInFlight;
    }

    @Override
    public Instant expiresAt(Job job) {
        return job.getLastUpdatedAt().plus(retention);
    }
}
