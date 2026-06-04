package com.async.request.reply.core.domain;

import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.exception.JobException;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public class Job {

    private final String id;
    private final String type;
    private final Object payload;
    private final Instant createdAt;
    private final AtomicReference<JobStatus> status = new AtomicReference<>(JobStatus.PENDING);
    private volatile Instant lastUpdatedAt;
    private volatile Object result;
    private volatile JobException failure;
    private volatile Integer percentComplete;

    public Job(String id, String type, Object payload) {
        this.id = id;
        this.type = type;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.lastUpdatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public Object getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public JobStatus getStatus() { return status.get(); }
    public Object getResult() { return result; }
    public JobException getFailure() { return failure; }
    public Integer getPercentComplete() { return percentComplete; }

    public boolean transitionTo(JobStatus expected, JobStatus next) {
        boolean transitioned = status.compareAndSet(expected, next);
        if (transitioned) lastUpdatedAt = Instant.now();
        return transitioned;
    }

    public void updateProgress(int percent) {
        this.percentComplete = percent;
        this.lastUpdatedAt = Instant.now();
    }

    public void complete(Object result) {
        this.result = result;
        this.percentComplete = 100;
        this.lastUpdatedAt = Instant.now();
        status.set(JobStatus.COMPLETED);
    }

    public void fail(JobException failure) {
        this.failure = failure;
        this.lastUpdatedAt = Instant.now();
        status.set(JobStatus.FAILED);
    }

    public boolean cancel() {
        JobStatus current = status.get();
        if (current == JobStatus.COMPLETED || current == JobStatus.FAILED || current == JobStatus.CANCELLED) {
            return false;
        }
        boolean cancelled = status.compareAndSet(current, JobStatus.CANCELLED);
        if (cancelled) lastUpdatedAt = Instant.now();
        return cancelled;
    }
}
