package com.async.request.reply.core.usecase;

import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.service.JobTransitionService;
import com.async.request.reply.spi.JobReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Implementação do {@link JobReporter}. Progresso e transições terminais passam
 * pelo {@link JobTransitionService}; a escrita de itens é guardada pelo estado do
 * job, para que um worker atrasado não altere o resultado de um job já concluído
 * (o que mudaria a paginação depois de "pronto") nem escreva lixo em um
 * cancelado.
 */
public class JobReporterUseCase implements JobReporter {

    private static final Logger log = LoggerFactory.getLogger(JobReporterUseCase.class);

    private final JobRepositoryPortOut repository;
    private final JobResultStorePortOut resultStore;
    private final JobTransitionService transition;

    public JobReporterUseCase(JobRepositoryPortOut repository,
                              JobResultStorePortOut resultStore,
                              JobTransitionService transition) {
        this.repository = repository;
        this.resultStore = resultStore;
        this.transition = transition;
    }

    @Override
    public boolean progress(String jobId, int percent) {
        return reported(jobId, "progress", transition.progress(jobId, percent));
    }

    @Override
    public boolean append(String jobId, List<?> items) {
        if (!isActive(jobId)) {
            return reported(jobId, "append", false);
        }
        resultStore.append(jobId, items);
        return true;
    }

    @Override
    public boolean complete(String jobId) {
        return reported(jobId, "complete", transition.complete(jobId));
    }

    @Override
    public boolean complete(String jobId, Object result) {
        // mesma guarda do append: sem isto, um complete tardio anexaria itens a um
        // job terminal antes de a transição ser recusada, deixando lixo no result
        if (!isActive(jobId)) {
            return reported(jobId, "complete", false);
        }
        return reported(jobId, "complete", transition.complete(jobId, result));
    }

    @Override
    public boolean fail(String jobId, String title, String detail) {
        return reported(jobId, "fail", transition.fail(jobId, title, detail));
    }

    private boolean isActive(String jobId) {
        return repository.findById(jobId)
                .map(job -> job.getStatus() == JobStatus.PENDING || job.getStatus() == JobStatus.PROCESSING)
                .orElse(false);
    }

    private static boolean reported(String jobId, String operation, boolean accepted) {
        if (!accepted) {
            log.debug("Report '{}' ignorado para o job '{}': inexistente ou em estado terminal",
                    operation, jobId);
        }
        return accepted;
    }
}
