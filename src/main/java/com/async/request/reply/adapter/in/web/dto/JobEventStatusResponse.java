package com.async.request.reply.adapter.in.web.dto;

import com.async.request.reply.core.enums.JobStatus;

import java.time.Instant;

/** Payload dos eventos SSE {@code status}, {@code progress}, {@code failed} e {@code cancelled}. */
public record JobEventStatusResponse(String jobId, JobStatus status, Integer percentComplete, Instant lastUpdatedAt) {
}
