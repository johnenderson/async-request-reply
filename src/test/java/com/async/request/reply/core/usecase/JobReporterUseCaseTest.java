package com.async.request.reply.core.usecase;

import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JobReporterUseCase")
class JobReporterUseCaseTest {

    private static final String JOB_ID = "job-1";
    private static final Optional<Instant> ACCEPTED = Optional.of(Instant.parse("2026-06-03T22:00:00Z"));

    private final JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);
    private final JobReporterUseCase reporter = new JobReporterUseCase(repository);

    @Test
    @DisplayName("delega progresso ao repositorio")
    void progress_should_delegate_to_the_repository() {
        when(repository.progress(JOB_ID, 40)).thenReturn(ACCEPTED);

        assertThat(reporter.progress(JOB_ID, 40)).isTrue();
        verify(repository).progress(JOB_ID, 40);
    }

    @Test
    @DisplayName("delega conclusao ao repositorio")
    void complete_should_delegate_to_the_repository() {
        when(repository.complete(JOB_ID)).thenReturn(ACCEPTED);

        assertThat(reporter.complete(JOB_ID)).isTrue();
        verify(repository).complete(JOB_ID);
    }

    @Test
    @DisplayName("delega falha ao repositorio")
    void fail_should_delegate_to_the_repository() {
        when(repository.fail(JOB_ID, "titulo", "detalhe")).thenReturn(ACCEPTED);

        assertThat(reporter.fail(JOB_ID, "titulo", "detalhe")).isTrue();
        verify(repository).fail(JOB_ID, "titulo", "detalhe");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("propaga a recusa quando o job nao esta mais ativo — o worker precisa saber para parar")
    @ValueSource(strings = {"progress", "complete", "fail"})
    void reports_should_return_false_when_the_transition_is_refused(String operation) {
        // Optional vazio = o UPDATE condicional nao encontrou job ativo
        when(repository.progress(JOB_ID, 10)).thenReturn(Optional.empty());
        when(repository.complete(JOB_ID)).thenReturn(Optional.empty());
        when(repository.fail(JOB_ID, "t", "d")).thenReturn(Optional.empty());

        boolean accepted = switch (operation) {
            case "progress" -> reporter.progress(JOB_ID, 10);
            case "complete" -> reporter.complete(JOB_ID);
            default -> reporter.fail(JOB_ID, "t", "d");
        };

        assertThat(accepted).isFalse();
    }
}
