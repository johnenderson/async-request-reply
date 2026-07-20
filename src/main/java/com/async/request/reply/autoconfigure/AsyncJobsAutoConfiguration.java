package com.async.request.reply.autoconfigure;

import com.async.request.reply.adapter.out.coalescing.TypeCoalescingKeyAdapterOut;
import com.async.request.reply.adapter.out.policy.DefaultJobPolicyAdapterOut;
import com.async.request.reply.adapter.out.policy.DefaultJobSubmissionPolicyAdapterOut;
import com.async.request.reply.adapter.out.processing.AsyncJobProcessorAdapterOut;
import com.async.request.reply.adapter.out.processing.JobHandlerRegistry;
import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;
import com.async.request.reply.core.service.JobTerminalTransitionService;
import com.async.request.reply.spi.Routine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.List;

/**
 * Auto-configuration núcleo da biblioteca: use cases, política, registry de
 * rotinas, processor e estratégia de coalescing. Não depende de web nem de uma
 * tecnologia de storage específica (essas ficam em auto-configs próprias).
 */
@AutoConfiguration(after = AsyncJobsRedisAutoConfiguration.class)
@EnableAsync
@EnableConfigurationProperties(AsyncJobsProperties.class)
@ComponentScan({"com.async.request.reply.core.usecase", "com.async.request.reply.core.service"})
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
                                            JobRepositoryPortOut repository,
                                            JobTerminalTransitionService terminal,
                                            JobEventPublisherPortOut events) {
        return new AsyncJobProcessorAdapterOut(registry, repository, terminal, events);
    }

    /** Fallback no-op: com SSE desligado, publicar eventos não custa nada. */
    @Bean
    @ConditionalOnMissingBean
    JobEventPublisherPortOut jobEventPublisherPortOut() {
        return event -> { };
    }

    @Bean
    @ConditionalOnMissingBean(CoalescingKeyPortOut.class)
    CoalescingKeyPortOut typeCoalescingKeyPortOut() {
        return new TypeCoalescingKeyAdapterOut();
    }
}
