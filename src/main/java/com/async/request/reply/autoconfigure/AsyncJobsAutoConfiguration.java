package com.async.request.reply.autoconfigure;

import com.async.request.reply.adapter.out.coalescing.PayloadCoalescingKeyAdapterOut;
import com.async.request.reply.adapter.out.coalescing.TypeCoalescingKeyAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.RedisJobRepositoryAdapaterOut;
import com.async.request.reply.adapter.out.persistence.redis.RedisSingleFlightAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.mapper.RedisJobMapper;
import com.async.request.reply.adapter.out.policy.DefaultJobPolicyAdapterOut;
import com.async.request.reply.adapter.out.processing.AsyncJobProcessorAdapterOut;
import com.async.request.reply.adapter.out.processing.JobHandlerRegistry;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/**
 * Auto-configuration da biblioteca de jobs assíncronos.
 *
 * <p>Registrada em {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * é carregada automaticamente pelo Spring Boot do projeto consumidor — basta
 * adicionar a dependência. Faz o component scan dos adapters e use cases da lib
 * e habilita @Async/@Scheduling necessários ao processamento.
 */
@AutoConfiguration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(AsyncJobsProperties.class)
@ComponentScan(basePackages = {
        "com.async.request.reply.adapter",
        "com.async.request.reply.core.usecase"
})
public class AsyncJobsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(JobPolicyPortOut.class)
    JobPolicyPortOut jobPolicyPortOut(AsyncJobsProperties properties) {
        return new DefaultJobPolicyAdapterOut(properties);
    }

    @Bean
    @ConditionalOnMissingBean(JobRepositoryPortOut.class)
    JobRepositoryPortOut jobRepositoryPortOut(RedissonClient redisson,
                                             ObjectMapper objectMapper,
                                             RedisJobMapper jobMapper,
                                             AsyncJobsProperties properties) {
        return new RedisJobRepositoryAdapaterOut(redisson, objectMapper, jobMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean(SingleFlightPortOut.class)
    SingleFlightPortOut singleFlightPortOut(RedissonClient redisson, AsyncJobsProperties properties) {
        return new RedisSingleFlightAdapterOut(redisson, properties);
    }

    @Bean
    @ConditionalOnMissingBean(JobProcessorPortOut.class)
    JobProcessorPortOut jobProcessorPortOut(JobHandlerRegistry registry,
                                            ObjectMapper objectMapper,
                                            JobRepositoryPortOut repository) {
        return new AsyncJobProcessorAdapterOut(registry, objectMapper, repository);
    }

    @Bean
    @ConditionalOnMissingBean(CoalescingKeyPortOut.class)
    @ConditionalOnProperty(name = "async-jobs.coalesce-key", havingValue = "type", matchIfMissing = true)
    CoalescingKeyPortOut typeCoalescingKeyPortOut() {
        return new TypeCoalescingKeyAdapterOut();
    }

    @Bean
    @ConditionalOnMissingBean(CoalescingKeyPortOut.class)
    @ConditionalOnProperty(name = "async-jobs.coalesce-key", havingValue = "payload")
    CoalescingKeyPortOut payloadCoalescingKeyPortOut(ObjectMapper objectMapper) {
        return new PayloadCoalescingKeyAdapterOut(objectMapper);
    }
}
