package com.async.request.reply.adapter.out.persistence.redis;

/**
 * Nomes das chaves usadas no Valkey/Redis, em um lugar só.
 *
 * <p>O prefixo opcional ({@code async-jobs.key-prefix}) isola aplicações que
 * compartilham o mesmo banco: sem ele, duas apps veem o mesmo índice de jobs
 * ativos e as mesmas chaves de idempotência — e a varredura de recuperação de uma
 * agiria sobre os jobs da outra.</p>
 */
public final class RedisKeys {

    private final String prefix;

    public RedisKeys(String prefix) {
        this.prefix = normalize(prefix);
    }

    private static String normalize(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.trim();
        return trimmed.endsWith(":") ? trimmed : trimmed + ":";
    }

    /** Hash com o estado do job. */
    public String job(String jobId) {
        return prefix + "job:" + jobId;
    }

    /** LIST com o resultado materializado (paginável por LRANGE). */
    public String result(String jobId) {
        return prefix + "job:" + jobId + ":result";
    }

    /** Tópico pub/sub dos eventos do job. */
    public String events(String jobId) {
        return prefix + "job:" + jobId + ":events";
    }

    /** Índice (sorted set) dos jobs ativos, base da recuperação de órfãos. */
    public String activeIndex() {
        return prefix + "jobs:active";
    }

    public String idempotency(String idempotencyKey) {
        return prefix + "idem:" + idempotencyKey;
    }

    public String inflight(String resourceKey) {
        return prefix + "inflight:" + resourceKey;
    }

    public String jobLock(String jobId) {
        return prefix + "lock:job:" + jobId;
    }

    public String idempotencyLock(String idempotencyKey) {
        return prefix + "lock:idem:" + idempotencyKey;
    }

    public String inflightLock(String resourceKey) {
        return prefix + "lock:inflight:" + resourceKey;
    }
}
