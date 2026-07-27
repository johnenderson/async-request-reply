package com.async.request.reply.adapter.in.web.dto;

/**
 * Resposta do {@code 202 Accepted}. Expõe as duas formas de acompanhar o job: o
 * stream de eventos (caminho normal, sem polling) e o endpoint de status
 * (recuperação, para quem reiniciou ou não abriu stream).
 */
public record SubmittedJobResponse(String jobId, String statusUrl, String eventsUrl) {}
