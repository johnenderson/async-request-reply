package com.async.request.reply;

import com.async.request.reply.adapter.out.events.PollingJobEventSubscriberAdapterOut;
import com.async.request.reply.adapter.out.persistence.jdbc.JdbcJobRepositoryAdapterOut;
import com.async.request.reply.adapter.out.recovery.ScheduledStaleJobReaperAdapterOut;
import com.async.request.reply.autoconfigure.AsyncJobsAutoConfiguration;
import com.async.request.reply.autoconfigure.AsyncJobsJdbcAutoConfiguration;
import com.async.request.reply.autoconfigure.AsyncJobsSseAutoConfiguration;
import com.async.request.reply.core.port.in.CancelJobPortIn;
import com.async.request.reply.core.port.in.GetJobStatusPortIn;
import com.async.request.reply.core.port.in.SubmitJobPortIn;
import com.async.request.reply.core.port.in.WatchJobPortIn;
import com.async.request.reply.core.port.out.JobEventSubscriberPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.service.StaleJobRecoveryService;
import com.async.request.reply.spi.JobReporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("AsyncJobsAutoConfiguration")
class AsyncJobsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AsyncJobsJdbcAutoConfiguration.class,
                    AsyncJobsAutoConfiguration.class,
                    AsyncJobsSseAutoConfiguration.class))
            // o DataSource e do projeto consumidor: a lib nao configura pool
            .withBean(DataSource.class, () -> mock(DataSource.class));

    @Test
    @DisplayName("liga o contrato completo sobre o storage relacional, sem configuracao extra")
    void should_wire_the_whole_contract_over_the_relational_storage() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(JobRepositoryPortOut.class)
                .hasSingleBean(JdbcJobRepositoryAdapterOut.class)
                .hasSingleBean(SubmitJobPortIn.class)
                .hasSingleBean(GetJobStatusPortIn.class)
                .hasSingleBean(CancelJobPortIn.class)
                .hasSingleBean(JobReporter.class)
                // eventos e recuperacao fazem parte do contrato, sem flag
                .hasSingleBean(WatchJobPortIn.class)
                .hasSingleBean(StaleJobRecoveryService.class)
                .hasSingleBean(ScheduledStaleJobReaperAdapterOut.class));
    }

    @Test
    @DisplayName("deriva os eventos do proprio repositorio, por polling (sem pub/sub)")
    void should_derive_events_from_the_repository_by_polling() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(JobEventSubscriberPortOut.class)
                .hasSingleBean(PollingJobEventSubscriberAdapterOut.class));
    }

    @Test
    @DisplayName("respeita o storage proprio do consumidor e ainda entrega o stream de eventos")
    void should_use_the_consumer_storage_when_it_provides_its_own_repository() {
        JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);

        contextRunner
                .withPropertyValues("async-jobs.storage=custom")
                .withBean(JobRepositoryPortOut.class, () -> repository)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(JdbcJobRepositoryAdapterOut.class);
                    assertThat(context.getBean(JobRepositoryPortOut.class)).isSameAs(repository);
                    // o subscriber por polling funciona sobre qualquer repositorio
                    assertThat(context).hasSingleBean(WatchJobPortIn.class);
                });
    }

    @Test
    @DisplayName("respeita um subscriber de eventos proprio do consumidor")
    void should_back_off_when_the_consumer_provides_its_own_event_subscriber() {
        JobEventSubscriberPortOut own = mock(JobEventSubscriberPortOut.class);

        contextRunner
                .withBean(JobEventSubscriberPortOut.class, () -> own)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PollingJobEventSubscriberAdapterOut.class);
                    assertThat(context.getBean(JobEventSubscriberPortOut.class)).isSameAs(own);
                });
    }

    @Test
    @DisplayName("permite desligar a recuperacao de jobs orfaos")
    void should_allow_disabling_the_stale_job_recovery() {
        contextRunner
                .withPropertyValues("async-jobs.recovery.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(StaleJobRecoveryService.class)
                        .doesNotHaveBean(ScheduledStaleJobReaperAdapterOut.class));
    }
}
