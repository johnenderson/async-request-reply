package com.async.request.reply.core.domain;

/**
 * Falha de um job como <b>dado</b> de domínio (não exceção): guarda a semântica
 * do erro ({@code title}/{@code detail}) sem capturar stack trace. A tradução
 * para HTTP/Problem Detail é responsabilidade do adapter web.
 */
public record JobFailure(String title, String detail) {
}
