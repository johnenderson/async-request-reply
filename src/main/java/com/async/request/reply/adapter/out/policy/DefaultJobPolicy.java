package com.async.request.reply.adapter.out.policy;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Adapter out: implementação default do {@link JobPolicyPortOut}.
 * Define os valores fixos da policy (retenção e intervalo de polling).
 * Trocar por uma versão configurável não toca em nenhum use case.
 */
@Component
public class DefaultJobPolicy implements JobPolicyPortOut {

    /** Tempo estimado de processamento — anunciado aos clients via Retry-After. */
    private static final int RETRY_AFTER_SECONDS = 5;

    /** Por quanto tempo um job e seu resultado são retidos antes da eviction. */
    private static final Duration RETENTION = Duration.ofHours(1);

    @Override
    public int retryAfterSeconds() {
        return RETRY_AFTER_SECONDS;
    }

    @Override
    public Instant expiresAt(Job job) {
        return job.getCreatedAt().plus(RETENTION);
    }
}
