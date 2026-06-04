package com.async.request.reply.autoconfigure;

import com.async.request.reply.adapter.out.coalescing.PayloadCoalescingKeyAdapterOut;
import com.async.request.reply.adapter.out.coalescing.TypeCoalescingKeyAdapterOut;
import com.async.request.reply.adapter.out.policy.DefaultJobPolicyAdapterOut;
import com.async.request.reply.adapter.out.policy.DefaultJobSubmissionPolicyAdapterOut;
import com.async.request.reply.adapter.out.processing.AsyncJobProcessorAdapterOut;
import com.async.request.reply.adapter.out.processing.JobHandlerRegistry;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;
import com.async.request.reply.spi.Routine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Auto-configuration núcleo da biblioteca: use cases, política, registry de
 * rotinas, processor e estratégia de coalescing. Não depende de web nem de uma
 * tecnologia de storage específica (essas ficam em auto-configs próprias).
 */
@AutoConfiguration(after = AsyncJobsRedisAutoConfiguration.class)
@EnableAsync
@EnableConfigurationProperties(AsyncJobsProperties.class)
@ComponentScan("com.async.request.reply.core.usecase")
public class AsyncJobsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JobPolicyPortOut jobPolicyPortOut(AsyncJobsProperties properties) {
        return new DefaultJobPolicyAdapterOut(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    JobSubmissionPolicyPortOut jobSubmissionPolicyPortOut(AsyncJobsProperties properties) {
        return new DefaultJobSubmissionPolicyAdapterOut(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    JobHandlerRegistry jobHandlerRegistry(List<Routine> routines) {
        return new JobHandlerRegistry(routines);
    }

    @Bean
    @ConditionalOnMissingBean
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
