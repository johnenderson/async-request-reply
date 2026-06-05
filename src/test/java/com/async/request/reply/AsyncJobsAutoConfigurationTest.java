package com.async.request.reply;

import com.async.request.reply.adapter.out.persistence.redis.RedisJobRepositoryAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.RedisSingleFlightAdapterOut;
import com.async.request.reply.adapter.out.persistence.redis.mapper.RedisJobMapper;
import com.async.request.reply.autoconfigure.AsyncJobsAutoConfiguration;
import com.async.request.reply.autoconfigure.AsyncJobsRedisAutoConfiguration;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
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
                    AsyncJobsAutoConfiguration.class))
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
                        .hasSingleBean(SingleFlightPortOut.class));
    }

    @Test
    void customStorageDisablesRedisAutoConfigurationAndUsesProvidedPorts() {
        JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);
        SingleFlightPortOut singleFlight = mock(SingleFlightPortOut.class);
        JobResultStorePortOut resultStore = mock(JobResultStorePortOut.class);

        contextRunner
                .withPropertyValues("async-jobs.storage=custom")
                .withBean(JobRepositoryPortOut.class, () -> repository)
                .withBean(SingleFlightPortOut.class, () -> singleFlight)
                .withBean(JobResultStorePortOut.class, () -> resultStore)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RedisJobMapper.class);
                    assertThat(context).doesNotHaveBean(RedisJobRepositoryAdapterOut.class);
                    assertThat(context).doesNotHaveBean(RedisSingleFlightAdapterOut.class);
                    assertThat(context).hasSingleBean(JobRepositoryPortOut.class);
                    assertThat(context).hasSingleBean(SingleFlightPortOut.class);
                    assertThat(context.getBean(JobRepositoryPortOut.class)).isSameAs(repository);
                    assertThat(context.getBean(SingleFlightPortOut.class)).isSameAs(singleFlight);
                });
    }
}
