package com.async.request.reply.adapter.in.web.dto;

import com.async.request.reply.core.exception.InvalidJobRequestException;

/**
 * Corpo do POST /jobs. Sem dependência de anotações Jackson: a validação é
 * explícita em {@link #of(Object, Object)}, recebendo {@code type} como
 * {@link Object} para:
 * <ul>
 *   <li>rejeitar coerção de escalar → string (ex: {@code {"type":123}});</li>
 *   <li>usar mensagens de domínio específicas para campos ausentes/ inválidos.</li>
 * </ul>
 */
public record SubmitJobRequest(String type, Object payload) {

    public static SubmitJobRequest of(Object type, Object payload) {
        if (!(type instanceof String s) || s.isBlank()) {
            throw new InvalidJobRequestException("O campo 'type' é obrigatório e deve ser textual.");
        }
        if (payload == null) {
            throw new InvalidJobRequestException("O campo 'payload' é obrigatório.");
        }
        return new SubmitJobRequest(s.trim(), payload);
    }
}
