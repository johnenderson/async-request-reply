package com.async.request.reply.adapter.out.persistence.redis;

import com.async.request.reply.autoconfigure.AsyncJobsProperties;
import com.async.request.reply.adapter.out.persistence.redis.dto.RedisJobHash;
import com.async.request.reply.adapter.out.persistence.redis.mapper.RedisJobMapper;
import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Adapter out: persistência de jobs em Valkey/Redis via API nativa do Redisson,
 * sem scripts Lua.
 *
 * <ul>
 *   <li>Idempotência: um {@link RLock} por chave serializa a criação; o hash
 *       do job é gravado antes do índice da idempotency key apontar para ele.</li>
 *   <li>Transições: {@link RLock} por job serializa cancel/complete/fail
 *       concorrentes, e o {@link RMap} é atualizado dentro do lock
 *       (check-and-set seguro entre instâncias).</li>
 * </ul>
 */
public class RedisJobRepositoryAdapterOut implements JobRepositoryPortOut {

    private static final String JOB_PREFIX = "job:";
    private static final String IDEM_PREFIX = "idem:";
    private static final String LOCK_PREFIX = "lock:job:";
    private static final String IDEM_LOCK_PREFIX = "lock:idem:";

    private static final Set<String> ACTIVE =
            Set.of(JobStatus.PENDING.name(), JobStatus.PROCESSING.name());

    private final RedissonClient redisson;
    private final RedisJobMapper jobMapper;
    private final Duration retention;

    public RedisJobRepositoryAdapterOut(RedissonClient redisson,
                                        RedisJobMapper jobMapper,
                                        AsyncJobsProperties properties) {
        this.redisson = redisson;
        this.jobMapper = jobMapper;
        this.retention = properties.resultTtl();
    }

    @Override
    public Job create(String id, String type, String idempotencyKey) {
        if (idempotencyKey == null) {
            writeHash(id, type);
            return new Job(id, type);
        }

        RLock lock = redisson.getLock(IDEM_LOCK_PREFIX + idempotencyKey);
        lock.lock();
        try {
            RBucket<String> idem = redisson.getBucket(IDEM_PREFIX + idempotencyKey, StringCodec.INSTANCE);
            String existingId = idem.get();
            if (existingId != null) {
                Optional<Job> existing = findById(existingId);
                if (existing.isPresent()) {
                    return existing.get();
                }
                idem.delete();
            }

            writeHash(id, type);
            idem.set(id, retention);
            return new Job(id, type);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void writeHash(String id, String type) {
        Map<String, String> fields = RedisJobHash.pending(type, Instant.now()).toMap();
        RMap<String, String> map = redisson.getMap(JOB_PREFIX + id, StringCodec.INSTANCE);
        map.putAll(fields);
        map.expire(retention);
    }

    @Override
    public Optional<Job> findById(String id) {
        RMap<String, String> map = redisson.getMap(JOB_PREFIX + id, StringCodec.INSTANCE);
        Map<String, String> h = map.readAllMap();
        return h.isEmpty() ? Optional.empty() : Optional.of(jobMapper.toDomain(id, h));
    }

    @Override
    public Optional<Job> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) return Optional.empty();
        String id = redisson.<String>getBucket(IDEM_PREFIX + idempotencyKey, StringCodec.INSTANCE).get();
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public boolean start(String id) {
        return transition(id, Set.of(JobStatus.PENDING.name()), RedisJobHash.processing(Instant.now()).toMap());
    }

    @Override
    public boolean complete(String id) {
        return transition(id, ACTIVE, RedisJobHash.completed(Instant.now()).toMap());
    }

    @Override
    public boolean fail(String id, String title, String detail) {
        return transition(id, ACTIVE, RedisJobHash.failed(title, detail, Instant.now()).toMap());
    }

    @Override
    public boolean cancel(String id) {
        return transition(id, ACTIVE, RedisJobHash.cancelled(Instant.now()).toMap());
    }

    @Override
    public boolean progress(String id, int percent) {
        return transition(id, ACTIVE, RedisJobHash.progress(percent, Instant.now()).toMap());
    }

    // --- helpers -----------------------------------------------------------

    /** Check-and-set serializado por {@link RLock} (atômico entre instâncias). */
    private boolean transition(String id, Set<String> allowedFrom, Map<String, String> patch) {
        RLock lock = redisson.getLock(LOCK_PREFIX + id);
        boolean locked;
        try {
            // espera até 2s; SEM lease fixo → watchdog do Redisson renova o lock
            // enquanto a thread o mantém (evita expirar no meio da seção crítica).
            locked = lock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!locked) return false;
        try {
            RMap<String, String> map = redisson.getMap(JOB_PREFIX + id, StringCodec.INSTANCE);
            String status = map.get(RedisJobHash.FIELD_STATUS);
            if (status == null || !allowedFrom.contains(status)) {
                return false; // não existe ou já terminal — não sobrescreve
            }
            map.putAll(patch);
            map.expire(retention);
            return true;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

}
