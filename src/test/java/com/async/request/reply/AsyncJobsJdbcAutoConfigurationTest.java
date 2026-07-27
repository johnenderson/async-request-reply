package com.async.request.reply;

import com.async.request.reply.adapter.out.persistence.jdbc.JdbcJobRepositoryAdapterOut;
import com.async.request.reply.autoconfigure.AsyncJobsJdbcAutoConfiguration;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("AsyncJobsJdbcAutoConfiguration")
class AsyncJobsJdbcAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncJobsJdbcAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    @DisplayName("registra o repositorio JDBC quando o storage e jdbc")
    void should_register_jdbc_repository_when_storage_is_jdbc() {
        contextRunner
                .withPropertyValues("async-jobs.storage=jdbc")
                .run(context -> assertThat(context)
                        .hasSingleBean(JobRepositoryPortOut.class)
                        .hasSingleBean(JdbcJobRepositoryAdapterOut.class));
    }

    @Test
    @DisplayName("registra o repositorio JDBC por omissao — jdbc e o storage default")
    void should_register_jdbc_repository_by_default() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(JdbcJobRepositoryAdapterOut.class));
    }

    @Test
    @DisplayName("recua quando o storage nao e jdbc")
    void should_back_off_when_storage_is_not_jdbc() {
        contextRunner
                .withPropertyValues("async-jobs.storage=custom")
                .run(context -> assertThat(context).doesNotHaveBean(JdbcJobRepositoryAdapterOut.class));
    }

    @Test
    @DisplayName("respeita um repositorio proprio do consumidor")
    void should_back_off_when_consumer_provides_its_own_repository() {
        JobRepositoryPortOut own = mock(JobRepositoryPortOut.class);

        contextRunner
                .withPropertyValues("async-jobs.storage=jdbc")
                .withBean(JobRepositoryPortOut.class, () -> own)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(JdbcJobRepositoryAdapterOut.class);
                    assertThat(context.getBean(JobRepositoryPortOut.class)).isSameAs(own);
                });
    }
}
