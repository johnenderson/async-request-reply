package com.async.request.reply.adapter.out.persistence.redis;

import com.async.request.reply.adapter.out.persistence.redis.dto.RedisJobHash;
import com.async.request.reply.adapter.out.persistence.redis.mapper.RedisJobMapper;
import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RMapAsync;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScoredSortedSetAsync;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 *   <li>Índice de ativos: um sorted set ({@code jobs:active}, score = última
 *       atualização) mantido no MESMO MULTI/EXEC da transição. É o que permite
 *       encontrar jobs órfãos sem varrer o keyspace.</li>
 * </ul>
 */
public class RedisJobRepositoryAdapterOut implements JobRepositoryPortOut {

    private static final Logger log = LoggerFactory.getLogger(RedisJobRepositoryAdapterOut.class);

    private static final Set<String> ACTIVE =
            Set.of(JobStatus.PENDING.name(), JobStatus.PROCESSING.name());

    private final RedissonClient redisson;
    private final RedisJobMapper jobMapper;
    private final RedisKeys keys;
    private final Duration retention;
    private final Clock clock;

    public RedisJobRepositoryAdapterOut(RedissonClient redisson,
                                        RedisJobMapper jobMapper,
                                        RedisKeys keys,
                                        AsyncJobsProperties properties,
                                        Clock clock) {
        this.redisson = redisson;
        this.jobMapper = jobMapper;
        this.keys = keys;
        this.retention = properties.resultTtl();
        this.clock = clock;
    }

    @Override
    public Job create(String id, String type, String idempotencyKey) {
        if (idempotencyKey == null) {
            return writeHash(id, type);
        }

        RLock lock = redisson.getLock(keys.idempotencyLock(idempotencyKey));
        lock.lock();
        try {
            RBucket<String> idem = redisson.getBucket(keys.idempotency(idempotencyKey), StringCodec.INSTANCE);
            String existingId = idem.get();
            if (existingId != null) {
                Optional<Job> existing = findById(existingId);
                if (existing.isPresent()) {
                    return existing.get();
                }
                idem.delete();
            }

            Job job = writeHash(id, type);
            idem.set(id, retention);
            return job;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Job writeHash(String id, String type) {
        Instant now = clock.instant();
        write(id, RedisJobHash.pending(type, now).toMap(), now, false);
        return Job.pending(id, type, now);
    }

    /**
     * Grava campos + TTL + índice de ativos em um único MULTI/EXEC: evita que
     * uma falha no meio deixe o hash sem TTL (leak permanente) ou o índice
     * dessincronizado do estado.
     */
    private void write(String id, Map<String, String> fields, Instant now, boolean terminal) {
        RBatch batch = redisson.createBatch(BatchOptions.defaults()
                .executionMode(BatchOptions.ExecutionMode.REDIS_WRITE_ATOMIC));

        RMapAsync<String, String> map = batch.getMap(keys.job(id), StringCodec.INSTANCE);
        map.putAllAsync(fields);
        map.expireAsync(retention);

        RScoredSortedSetAsync<String> active = batch.getScoredSortedSet(keys.activeIndex(), StringCodec.INSTANCE);
        if (terminal) {
            active.removeAsync(id);
        } else {
            active.addAsync(now.toEpochMilli(), id);
        }

        batch.execute();
    }

    @Override
    public Optional<Job> findById(String id) {
        RMap<String, String> map = redisson.getMap(keys.job(id), StringCodec.INSTANCE);
        Map<String, String> h = map.readAllMap();
        if (h.isEmpty() || h.get(RedisJobHash.FIELD_STATUS) == null) {
            return Optional.empty(); // inexistente ou hash sem o campo-chave
        }
        try {
            return Optional.of(jobMapper.toDomain(id, h));
        } catch (RuntimeException e) {
            // hash corrompido/parcial: trata como inexistente (404) em vez de
            // propagar 500 — mas registra, porque isso não deveria acontecer
            log.warn("Hash do job '{}' esta corrompido e sera tratado como inexistente: {}", id, h, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Job> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) return Optional.empty();
        String id = redisson.<String>getBucket(keys.idempotency(idempotencyKey), StringCodec.INSTANCE).get();
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public Optional<Instant> start(String id) {
        Instant now = clock.instant();
        return transition(id, Set.of(JobStatus.PENDING.name()),
                RedisJobHash.processing(now).toMap(), now, false);
    }

    @Override
    public Optional<Instant> complete(String id) {
        Instant now = clock.instant();
        return transition(id, ACTIVE, RedisJobHash.completed(now).toMap(), now, true);
    }

    @Override
    public Optional<Instant> fail(String id, String title, String detail) {
        Instant now = clock.instant();
        return transition(id, ACTIVE, RedisJobHash.failed(title, detail, now).toMap(), now, true);
    }

    @Override
    public Optional<Instant> cancel(String id) {
        Instant now = clock.instant();
        return transition(id, ACTIVE, RedisJobHash.cancelled(now).toMap(), now, true);
    }

    @Override
    public Optional<Instant> progress(String id, int percent) {
        // apenas PROCESSING: progresso implica execução em andamento, e o
        // evento publicado nunca anuncia um status diferente do persistido
        Instant now = clock.instant();
        return transition(id, Set.of(JobStatus.PROCESSING.name()),
                RedisJobHash.progress(percent, now).toMap(), now, false);
    }

    @Override
    public List<String> findStaleActive(Instant olderThan, int limit) {
        RScoredSortedSet<String> active = redisson.getScoredSortedSet(keys.activeIndex(), StringCodec.INSTANCE);
        return List.copyOf(active.valueRange(0, true, olderThan.toEpochMilli(), true, 0, limit));
    }

    @Override
    public void untrackActive(String id) {
        redisson.getScoredSortedSet(keys.activeIndex(), StringCodec.INSTANCE).remove(id);
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Check-and-set serializado por {@link RLock} (atômico entre instâncias).
     * Lock bloqueante SEM lease fixo → o watchdog do Redisson renova enquanto a
     * thread o mantém, e libera (~30s) se o holder morrer. Bloquear em vez de
     * tryLock evita que um timeout de lock seja confundido com "já terminal"
     * pelo caller.
     *
     * @return o instante gravado, ou vazio se a transição não era permitida
     */
    private Optional<Instant> transition(String id, Set<String> allowedFrom,
                                         Map<String, String> patch, Instant now, boolean terminal) {
        RLock lock = redisson.getLock(keys.jobLock(id));
        lock.lock();
        try {
            RMap<String, String> map = redisson.getMap(keys.job(id), StringCodec.INSTANCE);
            String status = map.get(RedisJobHash.FIELD_STATUS);
            if (status == null || !allowedFrom.contains(status)) {
                return Optional.empty(); // não existe ou já terminal — não sobrescreve
            }
            write(id, patch, now, terminal);
            return Optional.of(now);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
