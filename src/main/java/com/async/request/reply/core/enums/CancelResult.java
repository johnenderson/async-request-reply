package com.async.request.reply.core.enums;

/**
 * Outcome de uma requisição de cancelamento, livre de HTTP semantics
 * para que o controller decida o mapeamento de status-code.
 */
public enum CancelResult {
    NOT_FOUND,
    CANCELLED,
    ALREADY_TERMINAL
}
