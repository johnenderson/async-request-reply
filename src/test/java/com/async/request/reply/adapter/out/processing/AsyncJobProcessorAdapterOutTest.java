package com.async.request.reply.adapter.out.processing;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.spi.AsyncJobHandler;
import com.async.request.reply.spi.JobContext;
import com.async.request.reply.spi.JobHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AsyncJobProcessorAdapterOut")
class AsyncJobProcessorAdapterOutTest {

    private static final String JOB_ID = "job-1";
    private static final String TYPE = "contas";
    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");

    private final JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);

    private final Job job = Job.pending(JOB_ID, TYPE, NOW);

    private AsyncJobProcessorAdapterOut processor(Object... routines) {
        when(repository.start(JOB_ID)).thenReturn(Optional.of(NOW));
        JobHandlerRegistry registry = new JobHandlerRegistry(
                List.of(routines).stream().map(r -> (com.async.request.reply.spi.Routine) r).toList());
        return new AsyncJobProcessorAdapterOut(registry, repository);
    }

    @Test
    @DisplayName("roda a rotina sincrona e completa o job no retorno")
    void process_should_run_the_sync_routine_and_complete() {
        AtomicInteger runs = new AtomicInteger();
        processor(handler(_ -> runs.incrementAndGet())).process(job);

        assertThat(runs).hasValue(1);
        verify(repository).complete(JOB_ID);
    }

    @Test
    @DisplayName("nao roda a rotina quando o start e recusado — job ja cancelado")
    void process_should_not_run_the_routine_when_start_is_refused() {
        AtomicInteger runs = new AtomicInteger();
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler(_ -> runs.incrementAndGet())));
        when(repository.start(JOB_ID)).thenReturn(Optional.empty());

        new AsyncJobProcessorAdapterOut(registry, repository).process(job);

        assertThat(runs).hasValue(0);
        verify(repository, never()).complete(anyString());
    }

    @Test
    @DisplayName("marca como falho quando a rotina lanca excecao")
    void process_should_fail_the_job_when_the_routine_throws() {
        processor(handler(_ -> {
            throw new IllegalStateException("estourou");
        })).process(job);

        verify(repository).fail(JOB_ID, "Processing error", "estourou");
        verify(repository, never()).complete(anyString());
    }

    @Test
    @DisplayName("marca como falho quando nao existe rotina para o type")
    void process_should_fail_the_job_when_no_routine_matches_the_type() {
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler("outro-type", _ -> { })));
        when(repository.start(JOB_ID)).thenReturn(Optional.of(NOW));

        new AsyncJobProcessorAdapterOut(registry, repository).process(job);

        verify(repository).fail(eq(JOB_ID), eq("Unknown job type"), contains(TYPE));
    }

    @Test
    @DisplayName("rotina fire-and-forget nao e completada pela lib")
    void process_should_not_complete_a_fire_and_forget_routine() {
        AtomicReference<String> seen = new AtomicReference<>();
        AsyncJobHandler async = new AsyncJobHandler() {
            public String type() { return TYPE; }
            public void start(JobContext ctx) { seen.set(ctx.jobId()); }
        };
        when(repository.start(JOB_ID)).thenReturn(Optional.of(NOW));

        new AsyncJobProcessorAdapterOut(new JobHandlerRegistry(List.of(async)), repository).process(job);

        assertThat(seen).hasValue(JOB_ID);
        verify(repository, never()).complete(anyString());
    }

    // --- cancelamento cooperativo -------------------------------------------

    /**
     * Cancelar não interrompe a thread da rotina: o desvio consciente do ADR 0004
     * é resolvido de forma cooperativa — a rotina pergunta e decide parar.
     */
    @Test
    @DisplayName("a rotina ve isCancelled() virar true quando o job e cancelado no meio")
    void context_should_report_cancellation_while_the_routine_runs() {
        when(repository.findById(JOB_ID))
                .thenReturn(Optional.of(Job.pending(JOB_ID, TYPE, NOW)))
                .thenReturn(Optional.of(cancelled()));

        AtomicInteger chunks = new AtomicInteger();
        processor(handler(ctx -> {
            for (int i = 0; i < 10; i++) {
                if (ctx.isCancelled()) {
                    return; // para no meio, sem processar os chunks restantes
                }
                chunks.incrementAndGet();
            }
        })).process(job);

        assertThat(chunks)
                .as("a rotina deve parar na segunda pergunta, nao rodar os 10 chunks")
                .hasValue(1);
    }

    /**
     * Uma rotina que para sozinha ainda retorna normalmente, e a lib chamaria
     * {@code complete}. Quem protege o estado é o {@code UPDATE} condicional: ele
     * recusa a transição em job terminal, então o cancelamento não é sobrescrito.
     */
    @Test
    @DisplayName("complete de rotina que parou por cancelamento e recusado pelo repositorio")
    void complete_should_be_refused_after_cooperative_stop() {
        when(repository.findById(JOB_ID)).thenReturn(Optional.of(cancelled()));
        when(repository.complete(JOB_ID)).thenReturn(Optional.empty());

        processor(handler(ctx -> {
            if (ctx.isCancelled()) {
                return;
            }
            throw new AssertionError("deveria ter visto o cancelamento");
        })).process(job);

        assertThat(repository.complete(JOB_ID)).isEmpty();
    }

    @Test
    @DisplayName("isCancelled() e false para job que segue ativo")
    void context_should_report_not_cancelled_while_the_job_is_active() {
        when(repository.findById(JOB_ID)).thenReturn(Optional.of(Job.pending(JOB_ID, TYPE, NOW)));

        AtomicReference<Boolean> cancelled = new AtomicReference<>();
        processor(handler(ctx -> cancelled.set(ctx.isCancelled()))).process(job);

        assertThat(cancelled).hasValue(false);
    }

    /** Job que desapareceu do storage não é "cancelado" — não há o que parar. */
    @Test
    @DisplayName("isCancelled() e false quando o job nao e encontrado")
    void context_should_report_not_cancelled_when_the_job_is_gone() {
        when(repository.findById(JOB_ID)).thenReturn(Optional.empty());

        AtomicReference<Boolean> cancelled = new AtomicReference<>();
        processor(handler(ctx -> cancelled.set(ctx.isCancelled()))).process(job);

        assertThat(cancelled).hasValue(false);
    }

    // --- helpers -------------------------------------------------------------

    private static Job cancelled() {
        return Job.restore(JOB_ID, TYPE, JobStatus.CANCELLED, NOW, NOW, null, null);
    }

    private static JobHandler handler(java.util.function.Consumer<JobContext> body) {
        return handler(TYPE, body);
    }

    private static JobHandler handler(String type, java.util.function.Consumer<JobContext> body) {
        return new JobHandler() {
            public String type() { return type; }
            public void handle(JobContext ctx) { body.accept(ctx); }
        };
    }
}
