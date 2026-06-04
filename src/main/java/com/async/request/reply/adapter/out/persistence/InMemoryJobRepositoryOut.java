package com.async.request.reply.adapter.out.persistence;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter out: implementação in-memory do {@link JobRepositoryPortOut}.
 * Também aplica a retention policy via eviction agendada — um detalhe
 * de infraestrutura, por isso mora no adapter e não no port.
 */
@Repository
public class InMemoryJobRepositoryOut implements JobRepositoryPortOut {

    static final int RETENTION_HOURS = 1;

    private final ConcurrentHashMap<String, Job> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();

    @Override
    public Job save(Object payload, String idempotencyKey) {
        if (idempotencyKey != null) {
            String existingId = idempotencyIndex.get(idempotencyKey);
            if (existingId != null) {
                Job existing = store.get(existingId);
                if (existing != null) return existing;
            }
        }
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, payload);
        store.put(id, job);
        if (idempotencyKey != null) {
            idempotencyIndex.put(idempotencyKey, id);
        }
        return job;
    }

    @Override
    public Optional<Job> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /**
     * Retention policy: remove os jobs mais antigos que RETENTION_HOURS.
     * Executa a cada 15 minutos. Atende ao requisito da spec:
     * "Define a retention policy to clean them up after a reasonable period."
     */
    @Scheduled(fixedRateString = "PT15M")
    public void evictExpiredJobs() {
        Instant cutoff = Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS);
        store.entrySet().removeIf(entry -> entry.getValue().getCreatedAt().isBefore(cutoff));
        // Também limpa o idempotency index dos jobs removidos
        idempotencyIndex.entrySet().removeIf(entry -> !store.containsKey(entry.getValue()));
    }
}
