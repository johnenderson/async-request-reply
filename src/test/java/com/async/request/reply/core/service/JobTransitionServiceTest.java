package com.async.request.reply.core.service;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JobTransitionService")
class JobTransitionServiceTest {

    private static final Instant PERSISTED_AT = Instant.parse("2026-07-01T12:00:00Z");
    private static final String JOB_ID = "job-1";
    private static final String TYPE = "relatorio";

    private final JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);
    private final JobResultStorePortOut resultStore = mock(JobResultStorePortOut.class);
    private final JobEventPublisherPortOut events = mock(JobEventPublisherPortOut.class);
    private final RecordingSingleFlight singleFlight = new RecordingSingleFlight();
    private final CoalescingKeyPortOut coalescingKey = type -> type;

    private final Job job = Job.pending(JOB_ID, TYPE, PERSISTED_AT.minusSeconds(10));

    private JobTransitionService service(boolean coalesceInFlight) {
        JobSubmissionPolicyPortOut submissionPolicy = () -> coalesceInFlight;
        return new JobTransitionService(repository, resultStore, events, singleFlight,
                coalescingKey, submissionPolicy);
    }

    @Test
    @DisplayName("publica o evento com o instante que foi persistido, não um novo relógio")
    void complete_should_publish_event_with_the_persisted_instant() {
        when(repository.complete(JOB_ID)).thenReturn(Optional.of(PERSISTED_AT));

        assertThat(service(false).complete(job)).isTrue();

        ArgumentCaptor<JobEvent> published = ArgumentCaptor.forClass(JobEvent.class);
        verify(events).publish(published.capture());
        assertThat(published.getValue().lastUpdatedAt()).isEqualTo(PERSISTED_AT);
        assertThat(published.getValue().status()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    @DisplayName("não publica evento quando a transição é recusada")
    void complete_should_not_publish_when_transition_is_refused() {
        when(repository.complete(JOB_ID)).thenReturn(Optional.empty());

        assertThat(service(false).complete(job)).isFalse();
        verify(events, never()).publish(any(JobEvent.class));
    }

    @Test
    @DisplayName("libera o guard de single-flight na transição terminal quando há coalescing")
    void complete_should_release_single_flight_when_coalescing_is_enabled() {
        when(repository.complete(JOB_ID)).thenReturn(Optional.of(PERSISTED_AT));

        service(true).complete(job);

        assertThat(singleFlight.released).containsExactly(TYPE + "/" + JOB_ID);
    }

    @Test
    @DisplayName("não toca no guard quando coalescing está desligado")
    void complete_should_not_release_single_flight_when_coalescing_is_disabled() {
        when(repository.complete(JOB_ID)).thenReturn(Optional.of(PERSISTED_AT));

        service(false).complete(job);

        assertThat(singleFlight.released).isEmpty();
    }

    @ParameterizedTest(name = "titulo=[{0}]")
    @DisplayName("normaliza título em branco: um job FAILED sempre tem título")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void fail_should_normalize_blank_title(String blankTitle) {
        when(repository.fail(anyString(), anyString(), any())).thenReturn(Optional.of(PERSISTED_AT));

        service(false).fail(job, blankTitle, "detalhe");

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(repository).fail(anyString(), title.capture(), any());
        assertThat(title.getValue()).isNotBlank();
    }

    @Test
    @DisplayName("materializa o resultado antes de concluir")
    void complete_should_append_result_before_transition() {
        when(repository.complete(JOB_ID)).thenReturn(Optional.of(PERSISTED_AT));

        service(false).complete(job, List.of("a", "b"));

        verify(resultStore).append(JOB_ID, List.of("a", "b"));
        verify(repository).complete(JOB_ID);
    }

    /** Fake que registra as liberações do guard, sem Redis. */
    private static final class RecordingSingleFlight implements SingleFlightPortOut {

        private final List<String> released = new ArrayList<>();

        @Override
        public Optional<String> peek(String resourceKey) {
            return Optional.empty();
        }

        @Override
        public void claim(String resourceKey, String jobId) {
            // sem efeito no teste
        }

        @Override
        public void release(String resourceKey, String jobId) {
            released.add(resourceKey + "/" + jobId);
        }

        @Override
        public <T> T withLock(String resourceKey, Supplier<T> action) {
            return action.get();
        }
    }
}
