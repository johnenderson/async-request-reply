package com.async.request.reply.core.usecase;

import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.spi.JobReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementação do {@link JobReporter}. Cada método é uma transição atômica no
 * repositório: o {@code UPDATE} condicional recusa sozinho o report em job
 * terminal, sem precisar de leitura prévia nem de lock.
 */
public class JobReporterUseCase implements JobReporter {

    private static final Logger log = LoggerFactory.getLogger(JobReporterUseCase.class);

    private final JobRepositoryPortOut repository;

    public JobReporterUseCase(JobRepositoryPortOut repository) {
        this.repository = repository;
    }

    @Override
    public boolean progress(String jobId, int percent) {
        return reported(jobId, "progress", repository.progress(jobId, percent).isPresent());
    }

    @Override
    public boolean complete(String jobId) {
        return reported(jobId, "complete", repository.complete(jobId).isPresent());
    }

    @Override
    public boolean fail(String jobId, String title, String detail) {
        return reported(jobId, "fail", repository.fail(jobId, title, detail).isPresent());
    }

    private static boolean reported(String jobId, String operation, boolean accepted) {
        if (!accepted) {
            log.debug("Report '{}' ignorado para o job '{}': inexistente ou em estado terminal",
                    operation, jobId);
        }
        return accepted;
    }
}
