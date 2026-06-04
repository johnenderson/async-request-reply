package com.async.request.reply.core.exception;

import com.async.request.reply.core.enums.ErrorType;

/**
 * Lançada quando a submissão de um job falha na business validation.
 * Classificada como {@link ErrorType#VALIDATION}.
 */
public class InvalidJobRequestException extends JobException {

    public InvalidJobRequestException(String detail) {
        super("Invalid request", detail);
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.VALIDATION;
    }
}
