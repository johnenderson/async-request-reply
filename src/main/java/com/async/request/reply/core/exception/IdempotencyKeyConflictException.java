package com.async.request.reply.core.exception;

import com.async.request.reply.core.enums.ErrorType;

/**
 * Lançada quando uma {@code Idempotency-Key} é reutilizada para uma request
 * diferente da original (ex: outro {@code type}). Reusar a key só é válido
 * para retries da MESMA operação. Classificada como {@link ErrorType#CONFLICT}.
 */
public class IdempotencyKeyConflictException extends JobException {

    public IdempotencyKeyConflictException(String detail) {
        super("Idempotency-Key conflict", detail);
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONFLICT;
    }
}
