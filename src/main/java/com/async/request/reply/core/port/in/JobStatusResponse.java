package com.async.request.reply.core.port.in;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;

import java.time.Instant;

/**
 * Status body retornado para estados não-terminais (PENDING / PROCESSING).
 */
public record JobStatusResponse(
        String jobId,
        JobStatus status,
        Object result,
        Instant createdAt,
        Instant lastUpdatedAt,
        Integer percentComplete
) {
    public static JobStatusResponse from(Job job) {
        return new JobStatusResponse(
                job.getId(),
                job.getStatus(),
                job.getResult(),
                job.getCreatedAt(),
                job.getLastUpdatedAt(),
                job.getPercentComplete()
        );
    }
}
