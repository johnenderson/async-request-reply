package com.async.request.reply.adapter.out.persistence.redis;

import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobEventSubscriberPortOut;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Consumer;

/**
 * Adapter out: eventos de job via pub/sub do Valkey/Redis ({@link RTopic},
 * um tópico por job: {@code job:{id}:events}, payload JSON).
 *
 * <p>Pub/sub é a ponte entre instâncias: a transição pode acontecer na
 * instância que processa o job enquanto a assinatura (ex: stream SSE) vive em
 * outra. Sem retenção: quem assina recebe só o que for publicado dali em
 * diante — por isso o consumo começa com um snapshot do estado atual.</p>
 */
public class RedisJobEventsAdapterOut implements JobEventPublisherPortOut, JobEventSubscriberPortOut {

    private static final String TOPIC_PREFIX = "job:";
    private static final String TOPIC_SUFFIX = ":events";

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    public RedisJobEventsAdapterOut(RedissonClient redisson, ObjectMapper objectMapper) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
    }

    private RTopic topic(String jobId) {
        return redisson.getTopic(TOPIC_PREFIX + jobId + TOPIC_SUFFIX, StringCodec.INSTANCE);
    }

    @Override
    public void publish(JobEvent event) {
        topic(event.jobId()).publish(objectMapper.writeValueAsString(event));
    }

    @Override
    public AutoCloseable subscribe(String jobId, Consumer<JobEvent> listener) {
        RTopic topic = topic(jobId);
        int listenerId = topic.addListener(String.class, (channel, message) ->
                listener.accept(objectMapper.readValue(message, JobEvent.class)));
        return () -> topic.removeListener(listenerId);
    }
}
