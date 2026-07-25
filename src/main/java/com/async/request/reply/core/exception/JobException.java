package com.async.request.reply.core.exception;

import com.async.request.reply.core.enums.ErrorType;

/**
 * Exception base do domínio de jobs. Carrega apenas dados de domínio
 * ({@code title}, {@code detail} e um {@link ErrorType}) — nenhuma dependência
 * de HTTP. A tradução para Problem Detail / status code é responsabilidade
 * do adapter web.
 *
 * <p>É abstrata: cada subclasse declara explicitamente a sua classificação, em
 * vez de herdar um default genérico.</p>
 */
public abstract class JobException extends RuntimeException {

    private final String title;

    protected JobException(String title, String detail) {
        super(detail);
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    /** Classificação de domínio, traduzida para status HTTP pelo adapter web. */
    public abstract ErrorType errorType();
}
