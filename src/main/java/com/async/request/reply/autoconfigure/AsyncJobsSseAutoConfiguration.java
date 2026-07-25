package com.async.request.reply.autoconfigure;

import com.async.request.reply.adapter.in.web.sse.JobEventsControllerAdapterIn;
import com.async.request.reply.adapter.in.web.uribuilder.JobUriBuilder;
import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.port.in.WatchJobPortIn;
import com.async.request.reply.core.port.out.JobEventSubscriberPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.usecase.WatchJobUseCase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration do stream de eventos SSE ({@code GET /jobs/{id}/events}),
 * parte integrante do contrato da lib. Requer um {@link JobEventSubscriberPortOut}
 * no contexto — com {@code async-jobs.storage=redis} a lib registra o adapter
 * pub/sub; para storage próprio, o consumidor registra os beans dele. A ausência
 * falha o startup com mensagem explícita, em vez de omitir o endpoint em silêncio.
 */
@AutoConfiguration(after = {AsyncJobsRedisAutoConfiguration.class,
        AsyncJobsAutoConfiguration.class,
        AsyncJobsWebAutoConfiguration.class})
public class AsyncJobsSseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    WatchJobPortIn watchJobPortIn(JobRepositoryPortOut repository,
                                  ObjectProvider<JobEventSubscriberPortOut> subscriberProvider) {
        JobEventSubscriberPortOut subscriber = subscriberProvider.getIfAvailable();
        if (subscriber == null) {
            throw new IllegalStateException(
                    "O stream de eventos exige um JobEventSubscriberPortOut no contexto. "
                            + "Com async-jobs.storage=redis a lib registra o adapter pub/sub; para storage "
                            + "proprio, registre beans JobEventPublisherPortOut e JobEventSubscriberPortOut.");
        }
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
