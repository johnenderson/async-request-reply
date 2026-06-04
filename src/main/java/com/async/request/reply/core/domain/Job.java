package com.async.request.reply.core.domain;

import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.exception.JobException;

import java.time.Instant;

/**
 * Agregado imutável de leitura. As transições de estado são atômicas e moram
 * no adapter de persistência (Valkey/Lua), que é a fonte de verdade do status.
 */
public class Job {

    private final String id;
    private final String type;
    private final Object payload;
    private final JobStatus status;
    private final Instant createdAt;
    private final Instant lastUpdatedAt;
    private final Object result;
    private final JobException failure;
    private final Integer percentComplete;

    public Job(String id, String type, Object payload) {
        this(id, type, payload, JobStatus.PENDING, Instant.now(), Instant.now(), null, null, null);
    }

    private Job(String id, String type, Object payload, JobStatus status, Instant createdAt,
                Instant lastUpdatedAt, Object result, JobException failure, Integer percentComplete) {
        this.id = id;
        this.type = type;
        this.payload = payload;
        this.status = status;
        this.createdAt = createdAt;
        this.lastUpdatedAt = lastUpdatedAt;
        this.result = result;
        this.failure = failure;
        this.percentComplete = percentComplete;
    }

    /** Reconstrói um Job a partir de um estado persistido (ex: Valkey). */
    public static Job restore(String id, String type, Object payload, JobStatus status,
                              Instant createdAt, Instant lastUpdatedAt, Object result,
                              JobException failure, Integer percentComplete) {
        return new Job(id, type, payload, status, createdAt, lastUpdatedAt, result, failure, percentComplete);
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public Object getPayload() { return payload; }
    public JobStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public Object getResult() { return result; }
    public JobException getFailure() { return failure; }
    public Integer getPercentComplete() { return percentComplete; }
}
