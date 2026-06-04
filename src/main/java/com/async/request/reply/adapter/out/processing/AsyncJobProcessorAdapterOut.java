package com.async.request.reply.adapter.out.processing;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.spi.AsyncJobHandler;
import com.async.request.reply.spi.JobContext;
import com.async.request.reply.spi.JobHandler;
import com.async.request.reply.spi.Routine;
import org.springframework.scheduling.annotation.Async;
import org.springframework.core.ResolvableType;
import tools.jackson.databind.ObjectMapper;

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
    private final ObjectMapper objectMapper;
    private final JobRepositoryPortOut repository;

    public AsyncJobProcessorAdapterOut(JobHandlerRegistry registry, ObjectMapper objectMapper,
                                       JobRepositoryPortOut repository) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.repository = repository;
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
            repository.fail(job.getId(), "Unknown job type",
                    "Nenhuma routine registrada para o type '" + job.getType() + "'.");
            return;
        }

        try {
            switch (routine) {
                case JobHandler<?, ?> sync -> runSync(sync, job);
                case AsyncJobHandler<?> async -> runAsync(async, job);
                default -> repository.fail(job.getId(), "Unsupported routine",
                        "Tipo de routine não suportado: " + routine.getClass());
            }
        } catch (Exception e) {
            repository.fail(job.getId(), "Processing error", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void runSync(JobHandler<?, ?> handler, Job job) {
        Object input = convertPayload(job.getPayload(), handler, JobHandler.class);
        Object result = ((JobHandler<Object, ?>) handler).handle(input);
        repository.complete(job.getId(), result); // atômico: ignora se já cancelado
    }

    @SuppressWarnings("unchecked")
    private void runAsync(AsyncJobHandler<?> handler, Job job) {
        Object input = convertPayload(job.getPayload(), handler, AsyncJobHandler.class);
        JobContext ctx = job::getId; // expõe apenas o jobId
        ((AsyncJobHandler<Object>) handler).start(ctx, input);
        // NÃO completa: aguarda JobReporter.complete(jobId, ...) do worker
    }

    /** Converte o payload JSON para o tipo de input declarado pela routine. */
    private Object convertPayload(Object payload, Routine routine, Class<?> spi) {
        Class<?> inputType = ResolvableType.forClass(spi, routine.getClass())
                .getGeneric(0).resolve();
        if (inputType == null || inputType == Object.class || payload == null) {
            return payload;
        }
        return objectMapper.convertValue(payload, inputType);
    }
}
