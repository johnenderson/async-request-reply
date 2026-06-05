package com.async.request.reply.adapter.out.persistence.redis.dto;

import com.async.request.reply.core.enums.JobStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Representação do hash de metadados/estado do job no Redis/Valkey.
 * O resultado NÃO mora aqui — fica em uma LIST separada (result store).
 */
public record RedisJobHash(
        String type,
        String status,
        String createdAt,
        String lastUpdatedAt,
        String errorTitle,
        String errorDetail,
        String percentComplete) {

    public static final String FIELD_TYPE = "type";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_CREATED_AT = "createdAt";
    public static final String FIELD_LAST_UPDATED_AT = "lastUpdatedAt";
    public static final String FIELD_ERROR_TITLE = "errorTitle";
    public static final String FIELD_ERROR_DETAIL = "errorDetail";
    public static final String FIELD_PERCENT_COMPLETE = "percentComplete";

    public static RedisJobHash pending(String type, Instant now) {
        return new RedisJobHash(type, JobStatus.PENDING.name(), now.toString(), now.toString(),
                null, null, null);
    }

    public static RedisJobHash processing(Instant now) {
        return statusPatch(JobStatus.PROCESSING, now);
    }

    public static RedisJobHash completed(Instant now) {
        return new RedisJobHash(null, JobStatus.COMPLETED.name(), null, now.toString(),
                null, null, "100");
    }

    public static RedisJobHash failed(String title, String detail, Instant now) {
        return new RedisJobHash(null, JobStatus.FAILED.name(), null, now.toString(),
                title == null ? "" : title, detail == null ? "" : detail, null);
    }

    public static RedisJobHash cancelled(Instant now) {
        return statusPatch(JobStatus.CANCELLED, now);
    }

    public static RedisJobHash progress(int percent, Instant now) {
        return new RedisJobHash(null, null, null, now.toString(),
                null, null, String.valueOf(percent));
    }

    public Map<String, String> toMap() {
        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, FIELD_TYPE, type);
        putIfPresent(fields, FIELD_STATUS, status);
        putIfPresent(fields, FIELD_CREATED_AT, createdAt);
        putIfPresent(fields, FIELD_LAST_UPDATED_AT, lastUpdatedAt);
        putIfPresent(fields, FIELD_ERROR_TITLE, errorTitle);
        putIfPresent(fields, FIELD_ERROR_DETAIL, errorDetail);
        putIfPresent(fields, FIELD_PERCENT_COMPLETE, percentComplete);
        return fields;
    }

    private static RedisJobHash statusPatch(JobStatus status, Instant now) {
        return new RedisJobHash(null, status.name(), null, now.toString(),
                null, null, null);
    }

    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null) {
            fields.put(key, value);
        }
    }
}
