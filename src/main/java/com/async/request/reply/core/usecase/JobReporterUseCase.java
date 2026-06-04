package com.async.request.reply.core.usecase;

import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.spi.JobReporter;
import org.springframework.stereotype.Service;

/**
 * Implementação do {@link JobReporter}. Delega para as transições atômicas do
 * repositório — completar/falhar um job já terminal (ex: cancelado) é ignorado.
 */
@Service
public class JobReporterUseCase implements JobReporter {

    private final JobRepositoryPortOut repository;

    public JobReporterUseCase(JobRepositoryPortOut repository) {
        this.repository = repository;
    }

    @Override
    public void progress(String jobId, int percent) {
        repository.progress(jobId, percent);
    }

    @Override
    public void complete(String jobId, Object result) {
        repository.complete(jobId, result);
    }

    @Override
    public void fail(String jobId, String title, String detail) {
        repository.fail(jobId, title, detail);
    }
}
