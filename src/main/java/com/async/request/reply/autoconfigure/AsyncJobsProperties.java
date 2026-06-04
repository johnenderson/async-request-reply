package com.async.request.reply.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Propriedades da biblioteca. Valores ausentes assumem defaults; valores
 * explicitamente inválidos falham o startup (fail-fast) em vez de serem
 * silenciosamente corrigidos.
 */
@ConfigurationProperties(prefix = "async-jobs")
public record AsyncJobsProperties(
        Duration resultTtl,
        Integer retryAfterSeconds,
        boolean coalesceInFlight
) {

    public AsyncJobsProperties {
        resultTtl = (resultTtl == null) ? Duration.ofHours(1) : resultTtl;
        if (resultTtl.isZero() || resultTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "async-jobs.result-ttl deve ser positivo (ex.: PT1H), mas foi " + resultTtl);
        }

        retryAfterSeconds = (retryAfterSeconds == null) ? 5 : retryAfterSeconds;
        if (retryAfterSeconds <= 0) {
            throw new IllegalArgumentException(
                    "async-jobs.retry-after-seconds deve ser positivo, mas foi " + retryAfterSeconds);
        }
    }
}
