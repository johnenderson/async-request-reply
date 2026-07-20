package com.async.request.reply.core.enums;

/**
 * Classificação de erro em nível de domínio, agnóstica de HTTP.
 * O adapter web é quem traduz cada tipo para um status code.
 */
public enum ErrorType {
    /** Entrada inválida fornecida pelo cliente. */
    VALIDATION,
    /** A request conflita com um estado existente (ex: Idempotency-Key reusada com outra operação). */
    CONFLICT,
    /** O job foi aceito mas terminou em falha. */
    FAILED,
    /** Erro interno inesperado. */
    INTERNAL
}
