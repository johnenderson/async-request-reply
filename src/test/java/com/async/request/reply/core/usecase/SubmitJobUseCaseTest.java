package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.exception.IdempotencyKeyConflictException;
import com.async.request.reply.core.exception.InvalidJobRequestException;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import com.async.request.reply.core.result.SubmittedJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SubmitJobUseCase")
class SubmitJobUseCaseTest {

    private static final String TYPE = "relatorio";

    private final JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);
    private final JobProcessorPortOut processor = mock(JobProcessorPortOut.class);
    private final JobPolicyPortOut policy = mock(JobPolicyPortOut.class);
    private final CoalescingKeyPortOut coalescingKey = type -> type;
    private final RecordingSingleFlight singleFlight = new RecordingSingleFlight();

    @BeforeEach
    void setUp() {
        when(policy.retryAfterSeconds()).thenReturn(5);
        when(processor.supports(TYPE)).thenReturn(true);
        // create devolve um job novo com o id pedido (caminho "fresh")
        when(repository.create(anyString(), anyString(), any(), any()))
                .thenAnswer(call -> Job.pending(call.getArgument(0), call.getArgument(1), Instant.now()));
    }

    private SubmitJobUseCase useCase(boolean coalesceInFlight) {
        JobSubmissionPolicyPortOut submissionPolicy = () -> coalesceInFlight;
        return new SubmitJobUseCase(repository, processor, policy, submissionPolicy,
                singleFlight, coalescingKey);
    }

    @Test
    @DisplayName("reivindica o guard sob o lock, mas despacha só depois de liberá-lo")
    void execute_should_dispatch_outside_the_lock_when_coalescing_is_enabled() {
        doAnswer(call -> {
            singleFlight.dispatchedWhileLocked = singleFlight.locked;
            return null;
        }).when(processor).process(any(Job.class));

        useCase(true).execute(TYPE, null);

        assertThat(singleFlight.claimedWhileLocked)
                .as("o claim precisa ser serializado pelo lock da chave")
                .isTrue();
        assertThat(singleFlight.dispatchedWhileLocked)
                .as("enfileirar pode bloquear esperando vaga; segurar o lock travaria todos os submits do type")
                .isFalse();
    }

    @Test
    @DisplayName("reaproveita o job em andamento quando o guard aponta para um job ativo")
    void execute_should_return_owner_when_active_job_holds_the_key() {
        String owner = UUID.randomUUID().toString();
        singleFlight.owner = Optional.of(owner);
        when(repository.findById(owner)).thenReturn(Optional.of(Job.pending(owner, TYPE, Instant.now())));

        SubmittedJob submitted = useCase(true).execute(TYPE, null);

        assertThat(submitted.jobId()).isEqualTo(owner);
        verify(repository, never()).create(anyString(), anyString(), any(), any());
        verify(processor, never()).process(any(Job.class));
    }

    @Test
    @DisplayName("devolve o mesmo job para um retry da mesma Idempotency-Key")
    void execute_should_return_existing_job_when_idempotency_key_is_reused() {
        String existingId = UUID.randomUUID().toString();
        when(repository.findByIdempotencyKey("k1"))
                .thenReturn(Optional.of(Job.pending(existingId, TYPE, Instant.now())));

        SubmittedJob submitted = useCase(false).execute(TYPE, "k1");

        assertThat(submitted.jobId()).isEqualTo(existingId);
        verify(processor, never()).process(any(Job.class));
    }

    @Test
    @DisplayName("recusa a mesma Idempotency-Key usada para outro type")
    void execute_should_reject_when_idempotency_key_was_used_for_another_type() {
        when(repository.findByIdempotencyKey("k1"))
                .thenReturn(Optional.of(Job.pending(UUID.randomUUID().toString(), "outro-type", Instant.now())));

        assertThatThrownBy(() -> useCase(false).execute(TYPE, "k1"))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining("outro-type");
    }

    @Test
    @DisplayName("não redespacha quando o dedupe de idempotência vence a corrida")
    void execute_should_not_dispatch_when_repository_returns_a_deduplicated_job() {
        String otherId = UUID.randomUUID().toString();
        when(repository.create(anyString(), anyString(), any(), any()))
                .thenReturn(Job.pending(otherId, TYPE, Instant.now()));

        SubmittedJob submitted = useCase(false).execute(TYPE, "k1");

        assertThat(submitted.jobId()).isEqualTo(otherId);
        verify(processor, never()).process(any(Job.class));
    }

    @Test
    @DisplayName("aceita a submissão mesmo se o executor recusar o dispatch (a recuperação reenfileira)")
    void execute_should_still_accept_when_executor_rejects_the_dispatch() {
        doThrow(new TaskRejectedException("saturado")).when(processor).process(any(Job.class));

        SubmittedJob submitted = useCase(false).execute(TYPE, null);

        assertThat(submitted.jobId()).isNotBlank();
    }

    @Test
    @DisplayName("rejeita type sem routine registrada")
    void execute_should_reject_when_type_has_no_routine() {
        assertThatThrownBy(() -> useCase(false).execute("inexistente", null))
                .isInstanceOf(InvalidJobRequestException.class);
    }

    /** Fake de single-flight que registra se claim/dispatch ocorreram sob o lock. */
    private static final class RecordingSingleFlight implements SingleFlightPortOut {

        private Optional<String> owner = Optional.empty();
        private boolean locked;
        private boolean claimedWhileLocked;
        private Boolean dispatchedWhileLocked;

        @Override
        public Optional<String> peek(String resourceKey) {
            return owner;
        }

        @Override
        public void claim(String resourceKey, String jobId) {
            claimedWhileLocked = locked;
        }

        @Override
        public void release(String resourceKey, String jobId) {
            // sem efeito no teste
        }

        @Override
        public <T> T withLock(String resourceKey, Supplier<T> action) {
            locked = true;
            try {
                return action.get();
            } finally {
                locked = false;
            }
        }
    }
}
