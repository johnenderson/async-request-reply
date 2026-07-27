package com.async.request.reply.autoconfigure;

import com.async.request.reply.adapter.in.web.sse.JobEventsControllerAdapterIn;
import com.async.request.reply.adapter.in.web.uribuilder.JobUriBuilder;
import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.port.in.WatchJobPortIn;
import com.async.request.reply.core.port.out.JobEventSubscriberPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.usecase.WatchJobUseCase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration do stream de eventos SSE ({@code GET /jobs/{id}/events}),
 * parte integrante do contrato da lib — é ele que dispensa o polling do cliente
 * (ADR 0002). O {@link JobEventSubscriberPortOut} vem do
 * {@code AsyncJobsAutoConfiguration} — que deriva os eventos do próprio
 * repositório, por polling — ou do consumidor, quando ele registra o dele.
 */
@AutoConfiguration(after = {AsyncJobsJdbcAutoConfiguration.class,
        AsyncJobsAutoConfiguration.class,
        AsyncJobsWebAutoConfiguration.class})
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
                                                                  AsyncJobsProperties properties) {
            return new JobEventsControllerAdapterIn(watchJob, properties);
        }
    }
}
