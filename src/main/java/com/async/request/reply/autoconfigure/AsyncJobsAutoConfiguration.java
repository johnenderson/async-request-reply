package com.async.request.reply.autoconfigure;

import com.async.request.reply.adapter.out.coalescing.TypeCoalescingKeyAdapterOut;
import com.async.request.reply.adapter.out.policy.DefaultJobPolicyAdapterOut;
import com.async.request.reply.adapter.out.policy.DefaultJobSubmissionPolicyAdapterOut;
import com.async.request.reply.adapter.out.processing.AsyncJobProcessorAdapterOut;
import com.async.request.reply.adapter.out.processing.JobHandlerRegistry;
import com.async.request.reply.adapter.out.recovery.ScheduledStaleJobReaperAdapterOut;
import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.port.in.CancelJobPortIn;
import com.async.request.reply.core.port.in.GetJobResultPortIn;
import com.async.request.reply.core.port.in.GetJobStatusPortIn;
import com.async.request.reply.core.port.in.SubmitJobPortIn;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import com.async.request.reply.core.service.JobTransitionService;
import com.async.request.reply.core.service.StaleJobRecoveryService;
import com.async.request.reply.core.usecase.CancelJobUseCase;
import com.async.request.reply.core.usecase.GetJobResultUseCase;
import com.async.request.reply.core.usecase.GetJobStatusUseCase;
import com.async.request.reply.core.usecase.JobReporterUseCase;
import com.async.request.reply.core.usecase.SubmitJobUseCase;
import com.async.request.reply.spi.JobReporter;
import com.async.request.reply.spi.Routine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

import java.time.Clock;
import java.util.List;

/**
 * Auto-configuration núcleo da biblioteca e <b>composition root</b>: é o único
 * lugar onde adapters são ligados a use cases.
 *
 * <p>Os use cases e services do core são classes simples, sem anotação de
 * estereótipo Spring e sem component-scan — o wiring inteiro fica visível e
 * auditável aqui, e o core permanece independente de framework (conforme a skill
 * {@code hexagonal-architecture}).</p>
 */
@AutoConfiguration(after = AsyncJobsRedisAutoConfiguration.class)
@EnableAsync
@EnableConfigurationProperties(AsyncJobsProperties.class)
public class AsyncJobsAutoConfiguration {

    // --- políticas e infraestrutura de apoio -------------------------------

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
    @ConditionalOnMissingBean(CoalescingKeyPortOut.class)
    CoalescingKeyPortOut typeCoalescingKeyPortOut() {
        return new TypeCoalescingKeyAdapterOut();
    }

    /** Fonte de tempo injetável — permite testar TTL/expiração de forma determinística. */
    @Bean
    @ConditionalOnMissingBean
    Clock asyncJobsClock() {
        return Clock.systemUTC();
    }

    // --- processamento ------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    JobHandlerRegistry jobHandlerRegistry(List<Routine> routines) {
        return new JobHandlerRegistry(routines);
    }

    @Bean
    @ConditionalOnMissingBean
    JobProcessorPortOut jobProcessorPortOut(JobHandlerRegistry registry,
                                            JobTransitionService transition) {
        return new AsyncJobProcessorAdapterOut(registry, transition);
    }

    /**
     * Executor dedicado da lib, com <b>threads virtuais</b>: rotinas de longa
     * duração (a premissa do padrão) não competem com o {@code @Async} da
     * aplicação consumidora, e bloqueios de I/O não consomem thread de
     * plataforma. O limite de concorrência é backpressure: ao saturar, o submit
     * aguarda vaga em vez de acumular trabalho sem limite.
     */
    @Bean(AsyncJobProcessorAdapterOut.EXECUTOR_BEAN)
    @ConditionalOnMissingBean(name = AsyncJobProcessorAdapterOut.EXECUTOR_BEAN)
    AsyncTaskExecutor asyncJobsExecutor(AsyncJobsProperties properties) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("async-jobs-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(properties.processing().concurrencyLimit());
        executor.setTaskTerminationTimeout(30_000);
        return executor;
    }

    // --- core: serviços e use cases ----------------------------------------

    @Bean
    @ConditionalOnMissingBean
    JobTransitionService jobTransitionService(JobRepositoryPortOut repository,
                                             JobResultStorePortOut resultStore,
                                             JobEventPublisherPortOut events,
                                             SingleFlightPortOut singleFlight,
                                             CoalescingKeyPortOut coalescingKey,
                                             JobSubmissionPolicyPortOut submissionPolicy) {
        return new JobTransitionService(repository, resultStore, events, singleFlight,
                coalescingKey, submissionPolicy);
    }

    @Bean
    @ConditionalOnMissingBean
    SubmitJobPortIn submitJobPortIn(JobRepositoryPortOut repository,
                                    JobProcessorPortOut processor,
                                    JobPolicyPortOut policy,
                                    JobSubmissionPolicyPortOut submissionPolicy,
                                    SingleFlightPortOut singleFlight,
                                    CoalescingKeyPortOut coalescingKey) {
        return new SubmitJobUseCase(repository, processor, policy, submissionPolicy,
                singleFlight, coalescingKey);
    }

    @Bean
    @ConditionalOnMissingBean
    GetJobStatusPortIn getJobStatusPortIn(JobRepositoryPortOut repository, JobPolicyPortOut policy) {
        return new GetJobStatusUseCase(repository, policy);
    }

    @Bean
    @ConditionalOnMissingBean
    GetJobResultPortIn getJobResultPortIn(JobRepositoryPortOut repository,
                                          JobResultStorePortOut resultStore) {
        return new GetJobResultUseCase(repository, resultStore);
    }

    @Bean
    @ConditionalOnMissingBean
    CancelJobPortIn cancelJobPortIn(JobRepositoryPortOut repository, JobTransitionService transition) {
        return new CancelJobUseCase(repository, transition);
    }

    /** SPI injetada pelo projeto consumidor para reportar andamento e conclusão. */
    @Bean
    @ConditionalOnMissingBean
    JobReporter jobReporter(JobRepositoryPortOut repository,
                            JobResultStorePortOut resultStore,
                            JobTransitionService transition) {
        return new JobReporterUseCase(repository, resultStore, transition);
    }

    // --- recuperação de jobs órfãos ----------------------------------------

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "async-jobs.recovery.enabled", havingValue = "true", matchIfMissing = true)
    StaleJobRecoveryService staleJobRecoveryService(JobRepositoryPortOut repository,
                                                    JobProcessorPortOut processor,
                                                    JobTransitionService transition,
                                                    AsyncJobsProperties properties,
                                                    Clock clock) {
        return new StaleJobRecoveryService(repository, processor, transition, properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "async-jobs.recovery.enabled", havingValue = "true", matchIfMissing = true)
    ScheduledStaleJobReaperAdapterOut scheduledStaleJobReaper(StaleJobRecoveryService recovery,
                                                              AsyncJobsProperties properties) {
        return new ScheduledStaleJobReaperAdapterOut(recovery, properties);
    }
}
