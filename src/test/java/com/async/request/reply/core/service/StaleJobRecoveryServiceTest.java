package com.async.request.reply.core.service;

import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StaleJobRecoveryService")
class StaleJobRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");
    private static final String JOB_ID = "job-1";
    private static final String TYPE = "relatorio";

    private final JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);
    private final JobProcessorPortOut processor = mock(JobProcessorPortOut.class);
    private final JobTransitionService transition = mock(JobTransitionService.class);

    private StaleJobRecoveryService recovery;

    @BeforeEach
    void setUp() {
        // defaults: redispatch-after PT1M, processing-timeout PT15M, batch 100
        AsyncJobsProperties properties = new AsyncJobsProperties(null, null, false, null, null, null, null);
        recovery = new StaleJobRecoveryService(repository, processor, transition, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(processor.supports(TYPE)).thenReturn(true);
    }

    @ParameterizedTest(name = "{0} parado por {1}min → redespacha={2}, falha={3}")
    @DisplayName("aplica a política por status e tempo de inatividade")
    @CsvSource({
            "PENDING,     0,  false, false",
            "PENDING,     2,  true,  false",
            "PROCESSING,  2,  false, false",
            "PROCESSING,  30, false, true"
    })
    void recover_should_apply_policy_by_status_and_idle_time(JobStatus status, long idleMinutes,
                                                            boolean redispatched, boolean failed) {
        indexed(job(status, idleMinutes));

        recovery.recover();

        verify(processor, redispatched ? org.mockito.Mockito.times(1) : never()).process(any(Job.class));
        verify(transition, failed ? org.mockito.Mockito.times(1) : never())
                .fail(any(Job.class), anyString(), anyString());
    }

    @Test
    @DisplayName("ignora job de type não registrado nesta instância")
    void recover_should_ignore_job_when_type_is_not_registered_locally() {
        // outra aplicação (ou instância com outro conjunto de handlers) no mesmo banco
        indexed(Job.restore(JOB_ID, "type-de-outra-app", JobStatus.PENDING,
                NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(2)), null, null));

        int acted = recovery.recover();

        assertThat(acted).isZero();
        verify(processor, never()).process(any(Job.class));
        verify(transition, never()).fail(any(Job.class), anyString(), anyString());
    }

    @Test
    @DisplayName("remove do índice um id cujo job já expirou")
    void recover_should_untrack_when_job_no_longer_exists() {
        when(repository.findStaleActive(any(Instant.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(JOB_ID));
        when(repository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThat(recovery.recover()).isEqualTo(1);
        verify(repository).untrackActive(JOB_ID);
    }

    @Test
    @DisplayName("remove do índice um job que já está em estado terminal")
    void recover_should_untrack_when_indexed_job_is_terminal() {
        indexed(Job.restore(JOB_ID, TYPE, JobStatus.COMPLETED,
                NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(2)), null, 100));

        assertThat(recovery.recover()).isEqualTo(1);
        verify(repository).untrackActive(JOB_ID);
    }

    // --- helpers -------------------------------------------------------------

    private Job job(JobStatus status, long idleMinutes) {
        Instant lastUpdate = NOW.minus(Duration.ofMinutes(idleMinutes));
        return Job.restore(JOB_ID, TYPE, status, lastUpdate, lastUpdate, null, null);
    }

    private void indexed(Job job) {
        when(repository.findStaleActive(any(Instant.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(job.getId()));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
    }
}
