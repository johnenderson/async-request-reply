package com.async.request.reply.autoconfigure;

import com.async.request.reply.adapter.out.persistence.redis.RedisJobEventsAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.RedisJobRepositoryAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.RedisJobResultStoreAdapterOut;
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

/**
 * Auto-configuration do storage Redis/Valkey (via Redisson). Só ativa quando o
 * Redisson está no classpath e {@code async-jobs.storage=redis} (default). Um
 * consumidor pode fornecer seus próprios {@link JobRepositoryPortOut}/
 * {@link SingleFlightPortOut} e esta config recua ({@code @ConditionalOnMissingBean}).
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

    @Bean
    @ConditionalOnMissingBean
    JobRepositoryPortOut jobRepositoryPortOut(RedissonClient redisson,
                                              RedisJobMapper jobMapper,
                                              AsyncJobsProperties properties) {
        return new RedisJobRepositoryAdapterOut(redisson, jobMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    JobResultStorePortOut jobResultStorePortOut(RedissonClient redisson,
                                                ObjectMapper objectMapper,
                                                AsyncJobsProperties properties) {
        return new RedisJobResultStoreAdapterOut(redisson, objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    SingleFlightPortOut singleFlightPortOut(RedissonClient redisson, AsyncJobsProperties properties) {
        return new RedisSingleFlightAdapterOut(redisson, properties);
    }

    /** Pub/sub de eventos de job — só quando o stream SSE está habilitado. */
    @Bean
    @ConditionalOnProperty(name = "async-jobs.sse.enabled", havingValue = "true")
    @ConditionalOnMissingBean({JobEventPublisherPortOut.class, JobEventSubscriberPortOut.class})
    RedisJobEventsAdapterOut redisJobEventsAdapterOut(RedissonClient redisson, ObjectMapper objectMapper) {
        return new RedisJobEventsAdapterOut(redisson, objectMapper);
    }
}
