package com.async.request.reply.core.usecase;

import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.service.JobTransitionService;
import com.async.request.reply.spi.JobReporter;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Implementação do {@link JobReporter}. Os itens do resultado vão para o
 * result store (paginável); progresso e transições terminais passam pelo
 * {@link JobTransitionService} — completar um job já terminal (ex: cancelado)
 * é ignorado.
 */
@Service
public class JobReporterUseCase implements JobReporter {

    private final JobResultStorePortOut resultStore;
    private final JobTransitionService transition;

    public JobReporterUseCase(JobResultStorePortOut resultStore, JobTransitionService transition) {
        this.resultStore = resultStore;
        this.transition = transition;
    }

    @Override
    public void progress(String jobId, int percent) {
        transition.progress(jobId, percent);
    }

    @Override
    public void append(String jobId, List<?> items) {
        resultStore.append(jobId, items);
    }

    @Override
    public void complete(String jobId) {
        transition.complete(jobId);
    }

    @Override
    public void complete(String jobId, Object result) {
        transition.complete(jobId, result);
    }

    @Override
    public void fail(String jobId, String title, String detail) {
        transition.fail(jobId, title, detail);
    }
}
