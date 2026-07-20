package com.async.request.reply.adapter.out.processing;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.service.JobTerminalTransitionService;
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
 * As transições terminais (complete/fail) passam pelo
 * {@link JobTerminalTransitionService}, que garante evento + release do
 * single-flight junto com a transição.
 */
public class AsyncJobProcessorAdapterOut implements JobProcessorPortOut {

    private final JobHandlerRegistry registry;
    private final JobRepositoryPortOut repository;
    private final JobTerminalTransitionService terminal;
    private final JobEventPublisherPortOut events;

    public AsyncJobProcessorAdapterOut(JobHandlerRegistry registry, JobRepositoryPortOut repository,
                                       JobTerminalTransitionService terminal,
                                       JobEventPublisherPortOut events) {
        this.registry = registry;
        this.repository = repository;
        this.terminal = terminal;
        this.events = events;
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
        events.publish(new JobEvent(job.getId(), JobStatus.PROCESSING, null));

        Routine routine = registry.find(job.getType()).orElse(null);
        if (routine == null) {
            terminal.fail(job, "Unknown job type",
                    "Nenhuma routine registrada para o type '" + job.getType() + "'.");
            return;
        }

        try {
            switch (routine) {
                case JobHandler<?> sync -> terminal.complete(job, sync.handle());
                case AsyncJobHandler async -> runAsync(async, job);
                default -> terminal.fail(job, "Unsupported routine",
                        "Tipo de routine não suportado: " + routine.getClass());
            }
        } catch (Exception e) {
            terminal.fail(job, "Processing error", e.getMessage());
        }
    }

    private void runAsync(AsyncJobHandler handler, Job job) {
        JobContext ctx = job::getId; // expõe apenas o jobId
        handler.start(ctx);
        // NÃO completa: aguarda JobReporter.complete(jobId, ...) do worker
    }
}
