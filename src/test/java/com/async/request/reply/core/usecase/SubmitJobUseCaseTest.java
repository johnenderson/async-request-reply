package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.exception.IdempotencyKeyConflictException;
import com.async.request.reply.core.exception.InvalidJobRequestException;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobFreshnessPolicyPortOut;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;
import com.async.request.reply.core.result.SubmittedJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SubmitJobUseCase")
class SubmitJobUseCaseTest {

    private static final String TYPE = "relatorio";
    private static final Instant NOW = Instant.parse("2026-06-03T22:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(1);

    private final JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);
    private final JobProcessorPortOut processor = mock(JobProcessorPortOut.class);
    private final JobPolicyPortOut policy = mock(JobPolicyPortOut.class);
    private final CoalescingKeyPortOut coalescingKey = type -> type;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        when(policy.retryAfterSeconds()).thenReturn(5);
        when(processor.supports(TYPE)).thenReturn(true);
        // create devolve um job novo com o id pedido (caminho sem conflito)
        when(repository.create(anyString(), anyString(), any(), any()))
                .thenAnswer(call -> Job.pending(call.getArgument(0), call.getArgument(1), NOW));
    }

    /** @param freshness janela configurada para o type, ou {@code null} para frescor desligado. */
    private SubmitJobUseCase useCase(boolean coalesceInFlight, Duration freshness) {
        JobSubmissionPolicyPortOut submissionPolicy = () -> coalesceInFlight;
        JobFreshnessPolicyPortOut freshnessPolicy = _ -> Optional.ofNullable(freshness);
        return new SubmitJobUseCase(repository, processor, policy, submissionPolicy,
                freshnessPolicy, coalescingKey, clock);
    }

    private SubmitJobUseCase useCase(boolean coalesceInFlight) {
        return useCase(coalesceInFlight, null);
    }

    @Test
    @DisplayName("cria e despacha quando nao ha job ativo nem dado quente")
    void execute_should_create_and_dispatch_when_nothing_else_applies() {
        SubmittedJob submitted = useCase(true).execute(TYPE, null);

        assertThat(submitted.jobId()).isNotBlank();
        assertThat(submitted.retryAfterSeconds()).isEqualTo(5);
        verify(processor).process(any(Job.class));
    }

    @Test
    @DisplayName("delega o coalescing ao create, com o escopo do type como chave")
    void execute_should_delegate_coalescing_to_the_repository() {
        useCase(true).execute(TYPE, null);

        // o single-flight e resolvido pelo indice unico parcial dentro do create
        // (ADR 0004): o use case apenas informa o escopo, sem lock distribuido
        verify(repository).create(anyString(), eq(TYPE), eq(null), eq(TYPE));
    }

    @Test
    @DisplayName("nao envia escopo de coalescing quando o coalescing esta desligado")
    void execute_should_not_send_a_scope_when_coalescing_is_disabled() {
        useCase(false).execute(TYPE, null);

        verify(repository).create(anyString(), eq(TYPE), eq(null), eq(null));
    }

    @Test
    @DisplayName("reaproveita o job concluido quando o dado ainda esta quente")
    void execute_should_return_the_fresh_job_when_data_is_still_warm() {
        String freshId = UUID.randomUUID().toString();
        when(repository.findFreshCompleted(TYPE, NOW.minus(WINDOW)))
                .thenReturn(Optional.of(Job.pending(freshId, TYPE, NOW)));

        SubmittedJob submitted = useCase(true, WINDOW).execute(TYPE, null);

        assertThat(submitted.jobId()).isEqualTo(freshId);
        verify(repository, never()).create(anyString(), anyString(), any(), any());
        verify(processor, never()).process(any(Job.class));
    }

    @Test
    @DisplayName("refaz a carga quando o cliente forca refresh, mesmo com dado quente")
    void execute_should_create_a_new_job_when_the_client_forces_a_refresh() {
        when(repository.findFreshCompleted(anyString(), any()))
                .thenReturn(Optional.of(Job.pending(UUID.randomUUID().toString(), TYPE, NOW)));

        SubmittedJob submitted = useCase(true, WINDOW).execute(TYPE, null, true);

        verify(repository).create(eq(submitted.jobId()), eq(TYPE), eq(null), eq(TYPE));
        verify(processor).process(any(Job.class));
    }

    @Test
    @DisplayName("ignora o frescor quando nao ha janela configurada para o type")
    void execute_should_ignore_freshness_when_no_window_is_configured() {
        useCase(true, null).execute(TYPE, null);

        verify(repository, never()).findFreshCompleted(anyString(), any());
        verify(processor).process(any(Job.class));
    }

    @Test
    @DisplayName("devolve o mesmo job para um retry da mesma Idempotency-Key")
    void execute_should_return_existing_job_when_idempotency_key_is_reused() {
        String existingId = UUID.randomUUID().toString();
        when(repository.findByIdempotencyKey("k1"))
                .thenReturn(Optional.of(Job.pending(existingId, TYPE, NOW)));

        SubmittedJob submitted = useCase(false).execute(TYPE, "k1");

        assertThat(submitted.jobId()).isEqualTo(existingId);
        verify(processor, never()).process(any(Job.class));
    }

    @Test
    @DisplayName("recusa a mesma Idempotency-Key usada para outro type")
    void execute_should_reject_when_idempotency_key_was_used_for_another_type() {
        when(repository.findByIdempotencyKey("k1"))
                .thenReturn(Optional.of(Job.pending(UUID.randomUUID().toString(), "outro-type", NOW)));

        assertThatThrownBy(() -> useCase(false).execute(TYPE, "k1"))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining("outro-type");
    }

    @Test
    @DisplayName("nao redespacha quando o create devolve um job ja existente")
    void execute_should_not_dispatch_when_repository_returns_a_deduplicated_job() {
        String otherId = UUID.randomUUID().toString();
        when(repository.create(anyString(), anyString(), any(), any()))
                .thenReturn(Job.pending(otherId, TYPE, NOW));

        SubmittedJob submitted = useCase(false).execute(TYPE, "k1");

        assertThat(submitted.jobId()).isEqualTo(otherId);
        verify(processor, never()).process(any(Job.class));
    }

    @Test
    @DisplayName("recusa o job devolvido pelo create quando ele e de outro type")
    void execute_should_reject_when_the_deduplicated_job_has_another_type() {
        when(repository.create(anyString(), anyString(), any(), any()))
                .thenReturn(Job.pending(UUID.randomUUID().toString(), "outro-type", NOW));

        assertThatThrownBy(() -> useCase(false).execute(TYPE, "k1"))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    @DisplayName("aceita a submissao mesmo se o executor recusar o dispatch (a recuperacao reenfileira)")
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
}
