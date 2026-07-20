package com.async.request.reply.adapter.out.persistence.redis;

import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adapter out: single-flight sobre Valkey/Redis.
 *
 * <ul>
 *   <li>Guard: {@link RBucket} {@code inflight:{key}} com TTL
 *       (= async-jobs.result-ttl) como rede de segurança.</li>
 *   <li>{@link #withLock}: {@link RLock} por chave serializa a decisão
 *       peek → create → claim entre instâncias (watchdog do Redisson renova
 *       o lease enquanto a thread estiver viva).</li>
 *   <li>{@link #release}: compare-and-delete — só remove o guard se ele ainda
 *       apontar para o job informado, para não apagar um claim mais novo.</li>
 * </ul>
 */
public class RedisSingleFlightAdapterOut implements SingleFlightPortOut {

    private static final String PREFIX = "inflight:";
    private static final String LOCK_PREFIX = "lock:inflight:";

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
    public void claim(String resourceKey, String jobId) {
        bucket(resourceKey).set(jobId, ttl);
    }

    @Override
    public void release(String resourceKey, String jobId) {
        bucket(resourceKey).compareAndSet(jobId, null); // DEL somente se ainda for o dono
    }

    @Override
    public <T> T withLock(String resourceKey, Supplier<T> action) {
        RLock lock = redisson.getLock(LOCK_PREFIX + resourceKey);
        lock.lock();
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
