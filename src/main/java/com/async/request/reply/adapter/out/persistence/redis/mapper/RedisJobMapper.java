package com.async.request.reply.adapter.out.persistence.redis.mapper;

import com.async.request.reply.adapter.out.persistence.redis.dto.RedisJobHash;
import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.exception.JobFailedException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

@Component
public class RedisJobMapper {

    private final ObjectMapper objectMapper;

    public RedisJobMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Job toDomain(String id, Map<String, String> h) {
        String errorTitle = h.get("errorTitle");
        JobFailedException failure = (errorTitle != null && !errorTitle.isBlank())
                ? new JobFailedException(errorTitle, h.get("errorDetail"))
                : null;
        String percent = h.get("percentComplete");
        return Job.restore(
                id,
                h.get(RedisJobHash.TYPE),
                parseJson(h.get(RedisJobHash.PAYLOAD)),
                JobStatus.valueOf(h.get(RedisJobHash.STATUS)),
                Instant.parse(h.get(RedisJobHash.CREATED_AT)),
                Instant.parse(h.get(RedisJobHash.LAST_UPDATED_AT)),
                parseJson(h.get(RedisJobHash.RESULT)),
                failure,
                percent == null ? null : Integer.valueOf(percent));
    }

    private Object parseJson(String json) {
        return (json == null || "null".equals(json)) ? null : objectMapper.readValue(json, Object.class);
    }
}
