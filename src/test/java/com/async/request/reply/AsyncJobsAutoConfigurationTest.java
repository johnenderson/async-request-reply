package com.async.request.reply;

import com.async.request.reply.adapter.out.persistence.redis.RedisJobEventsAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.RedisJobRepositoryAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.RedisSingleFlightAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.mapper.RedisJobMapper;
import com.async.request.reply.adapter.out.recovery.ScheduledStaleJobReaperAdapterOut;
import com.async.request.reply.autoconfigure.AsyncJobsAutoConfiguration;
import com.async.request.reply.autoconfigure.AsyncJobsRedisAutoConfiguration;
import com.async.request.reply.autoconfigure.AsyncJobsSseAutoConfiguration;
import com.async.request.reply.core.port.in.WatchJobPortIn;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobEventSubscriberPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import com.async.request.reply.core.service.StaleJobRecoveryService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AsyncJobsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AsyncJobsRedisAutoConfiguration.class,
                    AsyncJobsAutoConfiguration.class,
                    AsyncJobsSseAutoConfiguration.class))
            .withBean(ObjectMapper.class, JsonMapper::new);

    @Test
    void redisStorageIsConfiguredByDefaultWhenRedissonIsAvailable() {
        contextRunner
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(RedisJobMapper.class)
                        .hasSingleBean(RedisJobRepositoryAdapterOut.class)
                        .hasSingleBean(RedisSingleFlightAdapterOut.class)
                        .hasSingleBean(JobRepositoryPortOut.class)
                        .hasSingleBean(SingleFlightPortOut.class)
                        // eventos e recuperação fazem parte do contrato, sem flag
                        .hasSingleBean(RedisJobEventsAdapterOut.class)
                        .hasSingleBean(WatchJobPortIn.class)
                        .hasSingleBean(StaleJobRecoveryService.class)
                        .hasSingleBean(ScheduledStaleJobReaperAdapterOut.class));
    }

    @Test
    void customStorageDisablesRedisAutoConfigurationAndUsesProvidedPorts() {
        JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);
        SingleFlightPortOut singleFlight = mock(SingleFlightPortOut.class);
        JobResultStorePortOut resultStore = mock(JobResultStorePortOut.class);
        JobEventPublisherPortOut publisher = mock(JobEventPublisherPortOut.class);
        JobEventSubscriberPortOut subscriber = mock(JobEventSubscriberPortOut.class);

        contextRunner
                .withPropertyValues("async-jobs.storage=custom")
                .withBean(JobRepositoryPortOut.class, () -> repository)
                .withBean(SingleFlightPortOut.class, () -> singleFlight)
                .withBean(JobResultStorePortOut.class, () -> resultStore)
                .withBean(JobEventPublisherPortOut.class, () -> publisher)
                .withBean(JobEventSubscriberPortOut.class, () -> subscriber)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RedisJobMapper.class);
                    assertThat(context).doesNotHaveBean(RedisJobRepositoryAdapterOut.class);
                    assertThat(context).doesNotHaveBean(RedisSingleFlightAdapterOut.class);
                    assertThat(context).doesNotHaveBean(RedisJobEventsAdapterOut.class);
                    assertThat(context).hasSingleBean(JobRepositoryPortOut.class);
                    assertThat(context).hasSingleBean(SingleFlightPortOut.class);
                    assertThat(context.getBean(JobRepositoryPortOut.class)).isSameAs(repository);
                    assertThat(context.getBean(SingleFlightPortOut.class)).isSameAs(singleFlight);
                });
    }

    /** Storage próprio sem os ports de eventos deve falhar com mensagem explícita. */
    @Test
    void customStorageWithoutEventPortsFailsFast() {
        contextRunner
                .withPropertyValues("async-jobs.storage=custom")
                .withBean(JobRepositoryPortOut.class, () -> mock(JobRepositoryPortOut.class))
                .withBean(SingleFlightPortOut.class, () -> mock(SingleFlightPortOut.class))
                .withBean(JobResultStorePortOut.class, () -> mock(JobResultStorePortOut.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void recoveryCanBeDisabled() {
        contextRunner
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withPropertyValues("async-jobs.recovery.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(StaleJobRecoveryService.class)
                        .doesNotHaveBean(ScheduledStaleJobReaperAdapterOut.class));
    }
}
