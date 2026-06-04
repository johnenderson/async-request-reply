package com.async.request.reply.core.exception;

import com.async.request.reply.core.enums.ErrorType;

/**
 * Exception base do domínio de jobs. Carrega apenas dados de domínio
 * ({@code title}, {@code detail} e um {@link ErrorType}) — nenhuma dependência
 * de HTTP. A tradução para Problem Detail / status code é responsabilidade
 * do adapter web.
 */
public class JobException extends RuntimeException {

    private final String title;

    public JobException(String title, String detail) {
        super(detail);
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    /** Classificação padrão: erro interno. Subclasses sobrescrevem. */
    public ErrorType errorType() {
        return ErrorType.INTERNAL;
    }
}
