package com.async.request.reply.adapter.out.processing;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
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
 * <p>Cada transição é um check-and-set atômico no repositório: {@code start}
 * recusa sozinho um job que já foi cancelado, e {@code complete} não sobrescreve
 * estado terminal (ADR 0004).</p>
 *
 * <p>Roda no executor próprio da lib ({@code asyncJobsExecutor}, threads
 * virtuais) — nunca no executor default da aplicação, para que rotinas de longa
 * duração não concorram com o {@code @Async} do projeto consumidor.</p>
 */
public class AsyncJobProcessorAdapterOut implements JobProcessorPortOut {

    /** Nome do executor dedicado da lib (threads virtuais). */
    public static final String EXECUTOR_BEAN = "asyncJobsExecutor";

    private final JobHandlerRegistry registry;
    private final JobRepositoryPortOut repository;

    public AsyncJobProcessorAdapterOut(JobHandlerRegistry registry, JobRepositoryPortOut repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @Override
    public boolean supports(String type) {
        return registry.supports(type);
    }

    @Override
    @Async(AsyncJobProcessorAdapterOut.EXECUTOR_BEAN)
    public void process(Job job) {
        // PENDING → PROCESSING; vazio = já cancelado ou tomado por outra instância
        if (repository.start(job.getId()).isEmpty()) {
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
                case JobHandler sync -> {
                    sync.handle(context(job));          // o efeito é a escrita na base do consumidor
                    // o retorno do handler é o sinal de conclusão; se ele parou por
                    // cancelamento, o UPDATE condicional recusa e o estado terminal
                    // permanece — não há "descancelar"
                    repository.complete(job.getId());
                }
                case AsyncJobHandler async -> runAsync(async, job);
                default -> repository.fail(job.getId(), "Unsupported routine",
                        "Tipo de routine não suportado: " + routine.getClass());
            }
        } catch (Exception e) {
            repository.fail(job.getId(), "Processing error", e.getMessage());
        }
    }

    private void runAsync(AsyncJobHandler handler, Job job) {
        handler.start(context(job));
        // NÃO completa: aguarda JobReporter.complete(jobId) do worker
    }

    /**
     * Contexto da rotina. O {@code isCancelled} lê o estado a cada chamada — é o
     * preço de não manter cache que possa mentir sobre um cancelamento recente.
     */
    private JobContext context(Job job) {
        return new JobContext() {

            @Override
            public String jobId() {
                return job.getId();
            }

            @Override
            public boolean isCancelled() {
                return repository.findById(job.getId())
                        .map(current -> current.getStatus() == JobStatus.CANCELLED)
                        .orElse(false);
            }
        };
    }
}
