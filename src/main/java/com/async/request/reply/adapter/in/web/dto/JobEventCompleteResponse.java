package com.async.request.reply.adapter.in.web.dto;

import java.time.Instant;

/**
 * Payload do evento SSE {@code complete}: "terminou, neste instante". Não aponta
 * para resultado — a lib não serve dados (ADR 0004), e o cliente lê o endpoint de
 * domínio dele ao receber este evento.
 */
public record JobEventCompleteResponse(String jobId, Instant lastUpdatedAt) {
}
