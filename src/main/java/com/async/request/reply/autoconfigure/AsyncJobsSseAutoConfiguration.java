package com.async.request.reply.autoconfigure;

import com.async.request.reply.adapter.in.web.sse.JobEventsControllerAdapterIn;
import com.async.request.reply.adapter.in.web.uribuilder.JobUriBuilder;
import com.async.request.reply.core.port.in.WatchJobPortIn;
import com.async.request.reply.core.port.out.JobEventSubscriberPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.usecase.WatchJobUseCase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration do stream de eventos SSE. Opt-in via
 * {@code async-jobs.sse.enabled=true}. Requer um {@link JobEventSubscriberPortOut}
 * no contexto — com {@code async-jobs.storage=redis} a lib registra o adapter
 * pub/sub; para storage próprio, o consumidor registra o bean dele.
 */
@AutoConfiguration(after = {AsyncJobsRedisAutoConfiguration.class,
        AsyncJobsAutoConfiguration.class,
        AsyncJobsWebAutoConfiguration.class})
@ConditionalOnProperty(name = "async-jobs.sse.enabled", havingValue = "true")
@ConditionalOnBean(JobEventSubscriberPortOut.class)
public class AsyncJobsSseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    WatchJobPortIn watchJobPortIn(JobRepositoryPortOut repository, JobEventSubscriberPortOut subscriber) {
        return new WatchJobUseCase(repository, subscriber);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = "async-jobs.web.enabled", havingValue = "true", matchIfMissing = true)
    static class SseWebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        JobEventsControllerAdapterIn jobEventsControllerAdapterIn(WatchJobPortIn watchJob,
                                                                  JobUriBuilder uris,
                                                                  AsyncJobsProperties properties) {
            return new JobEventsControllerAdapterIn(watchJob, uris, properties);
        }
    }
}
