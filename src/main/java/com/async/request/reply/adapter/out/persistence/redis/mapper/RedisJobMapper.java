package com.async.request.reply.adapter.out.persistence.redis.mapper;

import com.async.request.reply.adapter.out.persistence.redis.dto.RedisJobHash;
import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.domain.JobFailure;
import com.async.request.reply.core.enums.JobStatus;

import java.time.Instant;
import java.util.Map;

public class RedisJobMapper {

    /** Usado quando um job está FAILED sem título gravado (dado legado/parcial). */
    private static final String DEFAULT_FAILURE_TITLE = "Job failed";

    public Job toDomain(String id, Map<String, String> h) {
        JobStatus status = JobStatus.valueOf(h.get(RedisJobHash.FIELD_STATUS));
        String percent = h.get(RedisJobHash.FIELD_PERCENT_COMPLETE);
        return Job.restore(
                id,
                h.get(RedisJobHash.FIELD_TYPE),
                status,
                Instant.parse(h.get(RedisJobHash.FIELD_CREATED_AT)),
                Instant.parse(h.get(RedisJobHash.FIELD_LAST_UPDATED_AT)),
                failureOf(status, h),
                percent == null ? null : Integer.valueOf(percent));
    }

    /**
     * Um job FAILED <b>sempre</b> tem falha: se o título gravado estiver
     * ausente/em branco, sintetiza um padrão. Devolver {@code null} aqui faria
     * o adapter web quebrar ao renderizar o Problem Detail.
     */
    private static JobFailure failureOf(JobStatus status, Map<String, String> h) {
        if (status != JobStatus.FAILED) {
            return null;
        }
        String title = h.get(RedisJobHash.FIELD_ERROR_TITLE);
        String detail = h.get(RedisJobHash.FIELD_ERROR_DETAIL);
        return new JobFailure(
                (title == null || title.isBlank()) ? DEFAULT_FAILURE_TITLE : title,
                (detail == null || detail.isBlank()) ? null : detail);
    }
}
