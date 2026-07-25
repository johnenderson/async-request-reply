package com.async.request.reply.adapter.out.persistence.redis.mapper;

import com.async.request.reply.adapter.out.persistence.redis.dto.RedisJobHash;
import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.domain.JobFailure;
import com.async.request.reply.core.enums.JobStatus;

import java.time.Instant;
import java.util.Map;

public class RedisJobMapper {

    public Job toDomain(String id, Map<String, String> h) {
        String errorTitle = h.get(RedisJobHash.FIELD_ERROR_TITLE);
        JobFailure failure = (errorTitle != null && !errorTitle.isBlank())
                ? new JobFailure(errorTitle, h.get(RedisJobHash.FIELD_ERROR_DETAIL))
                : null;
        String percent = h.get(RedisJobHash.FIELD_PERCENT_COMPLETE);
        return Job.restore(
                id,
                h.get(RedisJobHash.FIELD_TYPE),
                JobStatus.valueOf(h.get(RedisJobHash.FIELD_STATUS)),
                Instant.parse(h.get(RedisJobHash.FIELD_CREATED_AT)),
                Instant.parse(h.get(RedisJobHash.FIELD_LAST_UPDATED_AT)),
                failure,
                percent == null ? null : Integer.valueOf(percent));
    }
}
