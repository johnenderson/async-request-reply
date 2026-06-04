package com.async.request.reply.core.exception;

import com.async.request.reply.core.enums.ErrorType;

/**
 * Representa um job que terminou no estado FAILED. Fica armazenada no Job
 * carregando a semântica da falha ({@code title}/{@code detail}).
 * Classificada como {@link ErrorType#FAILED}.
 */
public class JobFailedException extends JobException {

    public JobFailedException(String title, String detail) {
        super(title, detail);
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.FAILED;
    }
}
