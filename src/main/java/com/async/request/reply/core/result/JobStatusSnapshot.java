package com.async.request.reply.core.result;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;

import java.time.Instant;

/**
 * Snapshot de status retornado para estados não-terminais (PENDING / PROCESSING).
 * (Nome distinto do DTO web {@code JobStatusResponse} para evitar colisão.)
 */
public record JobStatusSnapshot(
        String jobId,
        JobStatus status,
        Instant createdAt,
        Instant lastUpdatedAt,
        Integer percentComplete
) {
    public static JobStatusSnapshot from(Job job) {
        return new JobStatusSnapshot(
                job.getId(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getLastUpdatedAt(),
                job.getPercentComplete()
        );
    }
}
