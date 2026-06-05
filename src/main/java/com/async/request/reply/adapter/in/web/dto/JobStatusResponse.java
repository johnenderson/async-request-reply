package com.async.request.reply.adapter.in.web.dto;

import com.async.request.reply.core.enums.JobStatus;

import java.time.Instant;

public record JobStatusResponse(
        String jobId,
        JobStatus status,
        Instant createdAt,
        Instant lastUpdatedAt,
        Integer percentComplete
) {}
