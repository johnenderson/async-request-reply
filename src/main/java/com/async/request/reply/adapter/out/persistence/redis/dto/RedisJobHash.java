package com.async.request.reply.adapter.out.persistence.redis.dto;

import com.async.request.reply.core.enums.JobStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record RedisJobHash(
        String type,
        String payload,
        String status,
        String createdAt,
        String lastUpdatedAt,
        String result,
        String errorTitle,
        String errorDetail,
        String percentComplete) {

    public static final String TYPE = "type";
    public static final String PAYLOAD = "payload";
    public static final String STATUS = "status";
    public static final String CREATED_AT = "createdAt";
    public static final String LAST_UPDATED_AT = "lastUpdatedAt";
    public static final String RESULT = "result";
    public static final String ERROR_TITLE = "errorTitle";
    public static final String ERROR_DETAIL = "errorDetail";
    public static final String PERCENT_COMPLETE = "percentComplete";

    public static RedisJobHash pending(String type, String payload, Instant now) {
        return new RedisJobHash(type, payload, JobStatus.PENDING.name(), now.toString(), now.toString(),
                null, null, null, null);
    }

    public static RedisJobHash processing(Instant now) {
        return statusPatch(JobStatus.PROCESSING, now);
    }

    public static RedisJobHash completed(String result, Instant now) {
        return new RedisJobHash(null, null, JobStatus.COMPLETED.name(), null, now.toString(),
                result, null, null, "100");
    }

    public static RedisJobHash failed(String title, String detail, Instant now) {
        return new RedisJobHash(null, null, JobStatus.FAILED.name(), null, now.toString(),
                null, title == null ? "" : title, detail == null ? "" : detail, null);
    }

    public static RedisJobHash cancelled(Instant now) {
        return statusPatch(JobStatus.CANCELLED, now);
    }

    public static RedisJobHash progress(int percent, Instant now) {
        return new RedisJobHash(null, null, null, null, now.toString(),
                null, null, null, String.valueOf(percent));
    }

    public Map<String, String> toMap() {
        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, TYPE, type);
        putIfPresent(fields, PAYLOAD, payload);
        putIfPresent(fields, STATUS, status);
        putIfPresent(fields, CREATED_AT, createdAt);
        putIfPresent(fields, LAST_UPDATED_AT, lastUpdatedAt);
        putIfPresent(fields, RESULT, result);
        putIfPresent(fields, ERROR_TITLE, errorTitle);
        putIfPresent(fields, ERROR_DETAIL, errorDetail);
        putIfPresent(fields, PERCENT_COMPLETE, percentComplete);
        return fields;
    }

    private static RedisJobHash statusPatch(JobStatus status, Instant now) {
        return new RedisJobHash(null, null, status.name(), null, now.toString(),
                null, null, null, null);
    }

    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null) {
            fields.put(key, value);
        }
    }
}
