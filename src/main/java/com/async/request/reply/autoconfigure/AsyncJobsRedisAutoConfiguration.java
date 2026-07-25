package com.async.request.reply.autoconfigure;

import com.async.request.reply.adapter.out.persistence.redis.RedisJobEventsAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.RedisJobRepositoryAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.RedisJobResultStoreAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.RedisKeys;
import com.async.request.reply.adapter.out.persistence.redis.RedisSingleFlightAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.mapper.RedisJobMapper;
import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobEventSubscriberPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Auto-configuration do storage Redis/Valkey (via Redisson). Só ativa quando o
 * Redisson está no classpath e {@code async-jobs.storage=redis} (default). Um
 * consumidor pode fornecer seus próprios ports e esta config recua
 * ({@code @ConditionalOnMissingBean}).
 */
@AutoConfiguration
@ConditionalOnClass(RedissonClient.class)
@ConditionalOnProperty(name = "async-jobs.storage", havingValue = "redis", matchIfMissing = true)
public class AsyncJobsRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RedisJobMapper redisJobMapper() {
        return new RedisJobMapper();
    }

    /** Nomes de chave (com o prefixo opcional de isolamento entre aplicações). */
    @Bean
    @ConditionalOnMissingBean
    RedisKeys redisKeys(AsyncJobsProperties properties) {
        return new RedisKeys(properties.keyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    JobRepositoryPortOut jobRepositoryPortOut(RedissonClient redisson,
                                              RedisJobMapper jobMapper,
                                              RedisKeys keys,
                                              AsyncJobsProperties properties,
                                              Clock clock) {
        return new RedisJobRepositoryAdapterOut(redisson, jobMapper, keys, properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    JobResultStorePortOut jobResultStorePortOut(RedissonClient redisson,
                                                ObjectMapper objectMapper,
                                                RedisKeys keys,
                                                AsyncJobsProperties properties) {
        return new RedisJobResultStoreAdapterOut(redisson, objectMapper, keys, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    SingleFlightPortOut singleFlightPortOut(RedissonClient redisson, RedisKeys keys,
                                            AsyncJobsProperties properties) {
        return new RedisSingleFlightAdapterOut(redisson, keys, properties);
    }

    /**
     * Pub/sub de eventos de job (publisher + subscriber). Usa um mapper próprio
     * (não o da aplicação) para que o formato de wire interno dos eventos não
     * dependa da config Jackson do consumidor.
     */
    @Bean
    @ConditionalOnMissingBean({JobEventPublisherPortOut.class, JobEventSubscriberPortOut.class})
    RedisJobEventsAdapterOut redisJobEventsAdapterOut(RedissonClient redisson, RedisKeys keys) {
        return new RedisJobEventsAdapterOut(redisson, keys);
    }
}
