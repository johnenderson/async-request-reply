package com.async.request.reply.adapter.out.processing;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.usecase.ReleaseSingleFlightUseCase;
import com.async.request.reply.spi.AsyncJobHandler;
import com.async.request.reply.spi.JobContext;
import com.async.request.reply.spi.JobHandler;
import com.async.request.reply.spi.Routine;
import org.springframework.scheduling.annotation.Async;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter out: implementação do {@link JobProcessorPortOut} que despacha o job
 * para a {@link Routine} correspondente ao seu {@code type}:
 *
 * <ul>
 *   <li>{@link JobHandler} (síncrono): roda e a lib auto-completa no retorno.</li>
 *   <li>{@link AsyncJobHandler} (fire-and-forget): apenas dispara; o job fica em
 *       PROCESSING até o worker reportar via JobReporter.</li>
 * </ul>
 */
public class AsyncJobProcessorAdapterOut implements JobProcessorPortOut {

    private final JobHandlerRegistry registry;
    private final JobRepositoryPortOut repository;
    private final JobResultStorePortOut resultStore;
    private final ReleaseSingleFlightUseCase releaseSingleFlight;

    public AsyncJobProcessorAdapterOut(JobHandlerRegistry registry, JobRepositoryPortOut repository,
                                       JobResultStorePortOut resultStore,
                                       ReleaseSingleFlightUseCase releaseSingleFlight) {
        this.registry = registry;
        this.repository = repository;
        this.resultStore = resultStore;
        this.releaseSingleFlight = releaseSingleFlight;
    }

    @Override
    public boolean supports(String type) {
        return registry.supports(type);
    }

    @Override
    @Async
    public void process(Job job) {
        // transição atômica PENDING → PROCESSING; se falhar, já foi cancelado/terminal
        if (!repository.start(job.getId())) {
            return;
        }

        Routine routine = registry.find(job.getType()).orElse(null);
        if (routine == null) {
            fail(job, "Unknown job type",
                    "Nenhuma routine registrada para o type '" + job.getType() + "'.");
            return;
        }

        try {
            switch (routine) {
                case JobHandler<?> sync -> runSync(sync, job);
                case AsyncJobHandler async -> runAsync(async, job);
                default -> fail(job, "Unsupported routine",
                        "Tipo de routine não suportado: " + routine.getClass());
            }
        } catch (Exception e) {
            fail(job, "Processing error", e.getMessage());
        }
    }

    private void runSync(JobHandler<?> handler, Job job) {
        Object result = handler.handle();
        resultStore.append(job.getId(), asList(result)); // materializa o resultado (paginável)
        repository.complete(job.getId());                // atômico: ignora se já cancelado
        releaseSingleFlight.release(job);                // guard não precisa esperar o TTL
    }

    private void fail(Job job, String title, String detail) {
        repository.fail(job.getId(), title, detail);
        releaseSingleFlight.release(job);
    }

    /** Normaliza o resultado: null → vazio, List → cópia, valor único → lista de 1. */
    private static List<Object> asList(Object result) {
        if (result == null) return List.of();
        if (result instanceof List<?> list) return new ArrayList<>(list);
        return List.of(result);
    }

    private void runAsync(AsyncJobHandler handler, Job job) {
        JobContext ctx = job::getId; // expõe apenas o jobId
        handler.start(ctx);
        // NÃO completa: aguarda JobReporter.complete(jobId, ...) do worker
    }
}
