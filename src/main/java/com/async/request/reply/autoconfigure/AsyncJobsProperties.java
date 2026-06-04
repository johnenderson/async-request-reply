package com.async.request.reply.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "async-jobs")
public record AsyncJobsProperties(
        Duration resultTtl,
        int retryAfterSeconds,
        boolean coalesceInFlight
) {

    public AsyncJobsProperties {
        resultTtl = resultTtl == null ? Duration.ofHours(1) : resultTtl;
        retryAfterSeconds = retryAfterSeconds > 0 ? retryAfterSeconds : 5;
    }
}
