package com.async.request.reply.adapter.in.web.dto;

import java.time.Instant;

/** Payload do evento SSE {@code complete}: aponta para o resultado paginado. */
public record JobEventCompleteResponse(String jobId, String resultUrl, Instant lastUpdatedAt) {
}
