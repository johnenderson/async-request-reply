package com.async.request.reply.adapter.in.web.dto;

import com.async.request.reply.core.exception.InvalidJobRequestException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Corpo do POST /jobs. A validação acontece num {@code @JsonCreator} estático
 * que recebe {@code type} como {@link Object} para:
 * <ul>
 *   <li>rejeitar coerção de escalar → string (ex: {@code {"type":123}});</li>
 *   <li>usar mensagens de domínio específicas (campo ausente vira
 *       {@link InvalidJobRequestException}, não o erro genérico do Jackson).</li>
 * </ul>
 */
public record SubmitJobRequest(String type, Object payload) {

    @JsonCreator
    static SubmitJobRequest from(
            @JsonProperty("type") Object type,
            @JsonProperty("payload") Object payload) {

        if (!(type instanceof String s) || s.isBlank()) {
            throw new InvalidJobRequestException("O campo 'type' é obrigatório e deve ser textual.");
        }
        if (payload == null) {
            throw new InvalidJobRequestException("O campo 'payload' é obrigatório.");
        }
        return new SubmitJobRequest(s.trim(), payload);
    }
}
