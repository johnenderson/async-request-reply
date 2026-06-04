package com.async.request.reply.adapter.out.persistence.redis;

import com.async.request.reply.autoconfigure.AsyncJobsProperties;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Optional;

/**
 * Adapter out: single-flight sobre Valkey/Redis via {@link RBucket#setIfAbsent}
 * (SETNX atômico com TTL). Garante 1 job em andamento por chave de recurso
 * entre múltiplas instâncias. TTL = async-jobs.result-ttl (rede de segurança).
 */
public class RedisSingleFlightAdapterOut implements SingleFlightPortOut {

    private static final String PREFIX = "inflight:";

    private final RedissonClient redisson;
    private final Duration ttl;

    public RedisSingleFlightAdapterOut(RedissonClient redisson, AsyncJobsProperties properties) {
        this.redisson = redisson;
        this.ttl = properties.resultTtl();
    }

    private RBucket<String> bucket(String resourceKey) {
        return redisson.getBucket(PREFIX + resourceKey, StringCodec.INSTANCE);
    }

    @Override
    public Optional<String> peek(String resourceKey) {
        return Optional.ofNullable(bucket(resourceKey).get());
    }

    @Override
    public String begin(String resourceKey, String candidateJobId) {
        RBucket<String> bucket = bucket(resourceKey);
        if (bucket.setIfAbsent(candidateJobId, ttl)) {
            return candidateJobId;
        }
        String existing = bucket.get();
        return existing != null ? existing : candidateJobId;
    }

    @Override
    public void takeOver(String resourceKey, String jobId) {
        bucket(resourceKey).set(jobId, ttl);
    }
}
