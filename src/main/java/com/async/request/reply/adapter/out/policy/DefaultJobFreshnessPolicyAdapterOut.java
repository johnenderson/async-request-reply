package com.async.request.reply.adapter.out.policy;

import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.port.out.JobFreshnessPolicyPortOut;

import java.time.Duration;
import java.util.Optional;

/**
 * Adapter out: janela de frescor a partir da configuração.
 */
public class DefaultJobFreshnessPolicyAdapterOut implements JobFreshnessPolicyPortOut {

    private final AsyncJobsProperties.Freshness freshness;

    public DefaultJobFreshnessPolicyAdapterOut(AsyncJobsProperties.Freshness freshness) {
        this.freshness = freshness;
    }

    @Override
    public Optional<Duration> freshnessFor(String type) {
        if (!freshness.enabled()) {
            return Optional.empty();
        }
        Duration perType = freshness.perType().get(type);
        return Optional.ofNullable(perType != null ? perType : freshness.defaultWindow());
    }
}
