package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.service.JobTransitionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JobReporterUseCase")
class JobReporterUseCaseTest {

    private static final String JOB_ID = "job-1";

    private final JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);
    private final JobResultStorePortOut resultStore = mock(JobResultStorePortOut.class);
    private final JobTransitionService transition = mock(JobTransitionService.class);

    private final JobReporterUseCase reporter =
            new JobReporterUseCase(repository, resultStore, transition);

    @ParameterizedTest(name = "status {0}")
    @DisplayName("recusa append em job terminal, para não alterar resultado já publicado")
    @EnumSource(value = JobStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void append_should_be_refused_when_job_is_terminal(JobStatus terminal) {
        existing(terminal);

        assertThat(reporter.append(JOB_ID, List.of("tarde-demais"))).isFalse();
        verify(resultStore, never()).append(anyString(), any());
    }

    @ParameterizedTest(name = "status {0}")
    @DisplayName("aceita append enquanto o job está ativo")
    @EnumSource(value = JobStatus.class, names = {"PENDING", "PROCESSING"})
    void append_should_be_accepted_when_job_is_active(JobStatus active) {
        existing(active);

        assertThat(reporter.append(JOB_ID, List.of("item"))).isTrue();
        verify(resultStore).append(JOB_ID, List.of("item"));
    }

    @Test
    @DisplayName("recusa complete-com-resultado em job terminal antes de anexar")
    void completeWithResult_should_be_refused_when_job_is_terminal() {
        existing(JobStatus.CANCELLED);

        assertThat(reporter.complete(JOB_ID, "resultado")).isFalse();
        verify(resultStore, never()).append(anyString(), any());
        verify(transition, never()).complete(anyString(), any());
    }

    @Test
    @DisplayName("recusa report quando o job não existe mais")
    void append_should_be_refused_when_job_does_not_exist() {
        when(repository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThat(reporter.append(JOB_ID, List.of("x"))).isFalse();
    }

    @Test
    @DisplayName("delega progresso ao serviço de transição")
    void progress_should_delegate_to_transition_service() {
        when(transition.progress(JOB_ID, 40)).thenReturn(true);

        assertThat(reporter.progress(JOB_ID, 40)).isTrue();
        verify(transition).progress(JOB_ID, 40);
    }

    private void existing(JobStatus status) {
        Instant now = Instant.parse("2026-07-01T12:00:00Z");
        when(repository.findById(JOB_ID))
                .thenReturn(Optional.of(Job.restore(JOB_ID, "relatorio", status, now, now, null, null)));
    }
}
