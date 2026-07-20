package com.async.request.reply.adapter.out.persistence.redis;

import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.result.JobResultPage;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter out: resultado materializado como LIST do Valkey/Redis.
 *
 * <ul>
 *   <li>{@link #append} → RPUSH dos itens serializados (JSON) + TTL.</li>
 *   <li>{@link #page} → LLEN (total) + LRANGE (fatia), paginação nativa sem
 *       carregar o resultado inteiro em memória.</li>
 * </ul>
 */
public class RedisJobResultStoreAdapterOut implements JobResultStorePortOut {

    private static final String RESULT_SUFFIX = ":result";

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final Duration retention;

    public RedisJobResultStoreAdapterOut(RedissonClient redisson, ObjectMapper objectMapper,
                                         AsyncJobsProperties properties) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.retention = properties.resultTtl();
    }

    private RList<String> list(String id) {
        return redisson.getList("job:" + id + RESULT_SUFFIX, StringCodec.INSTANCE);
    }

    @Override
    public void append(String id, List<?> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        RList<String> list = list(id);
        List<String> serialized = new ArrayList<>(items.size());
        for (Object item : items) {
            serialized.add(objectMapper.writeValueAsString(item));
        }
        list.addAll(serialized); // RPUSH
        list.expire(retention);
    }

    @Override
    public JobResultPage page(String id, int page, int size) {
        int safeSize = Math.max(size, 1);
        int safePage = Math.max(page, 0);

        RList<String> list = list(id);
        long total = list.size(); // LLEN

        long fromL = (long) safePage * safeSize;
        int from = (int) Math.min(fromL, total);
        int toExclusive = (int) Math.min(fromL + safeSize, total);

        List<Object> content = new ArrayList<>();
        if (from < toExclusive) {
            for (String raw : list.range(from, toExclusive - 1)) { // LRANGE (inclusivo)
                content.add(objectMapper.readValue(raw, Object.class));
            }
        }
        int totalPages = (int) Math.ceil((double) total / safeSize);
        return new JobResultPage(content, safePage, safeSize, total, totalPages);
    }
}
