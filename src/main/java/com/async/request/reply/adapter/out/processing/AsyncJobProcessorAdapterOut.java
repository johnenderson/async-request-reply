package com.async.request.reply.adapter.out.processing;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.service.JobTransitionService;
import com.async.request.reply.spi.AsyncJobHandler;
import com.async.request.reply.spi.JobContext;
import com.async.request.reply.spi.JobHandler;
import com.async.request.reply.spi.Routine;
import org.springframework.scheduling.annotation.Async;

/**
 * Adapter out: implementação do {@link JobProcessorPortOut} que despacha o job
 * para a {@link Routine} correspondente ao seu {@code type}:
 *
 * <ul>
 *   <li>{@link JobHandler} (síncrono): roda e a lib auto-completa no retorno.</li>
 *   <li>{@link AsyncJobHandler} (fire-and-forget): apenas dispara; o job fica em
 *       PROCESSING até o worker reportar via JobReporter.</li>
 * </ul>
 *
 * Todas as transições (start/complete/fail) passam pelo
 * {@link JobTransitionService}, que garante evento + release do single-flight.
 */
public class AsyncJobProcessorAdapterOut implements JobProcessorPortOut {

    private final JobHandlerRegistry registry;
    private final JobTransitionService transition;

    public AsyncJobProcessorAdapterOut(JobHandlerRegistry registry, JobTransitionService transition) {
        this.registry = registry;
        this.transition = transition;
    }

    @Override
    public boolean supports(String type) {
        return registry.supports(type);
    }

    @Override
    @Async
    public void process(Job job) {
        // transição atômica PENDING → PROCESSING; se falhar, já foi cancelado/terminal
        if (!transition.start(job)) {
            return;
        }

        Routine routine = registry.find(job.getType()).orElse(null);
        if (routine == null) {
            transition.fail(job, "Unknown job type",
                    "Nenhuma routine registrada para o type '" + job.getType() + "'.");
            return;
        }

        try {
            switch (routine) {
                case JobHandler<?> sync -> transition.complete(job, sync.handle());
                case AsyncJobHandler async -> runAsync(async, job);
                default -> transition.fail(job, "Unsupported routine",
                        "Tipo de routine não suportado: " + routine.getClass());
            }
        } catch (Exception e) {
            transition.fail(job, "Processing error", e.getMessage());
        }
    }

    private void runAsync(AsyncJobHandler handler, Job job) {
        JobContext ctx = job::getId; // expõe apenas o jobId
        handler.start(ctx);
        // NÃO completa: aguarda JobReporter.complete(jobId, ...) do worker
    }
}
