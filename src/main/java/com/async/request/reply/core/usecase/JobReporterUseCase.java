package com.async.request.reply.core.usecase;

import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.service.JobTerminalTransitionService;
import com.async.request.reply.spi.JobReporter;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Implementação do {@link JobReporter}. Os itens do resultado vão para o
 * result store (paginável); as transições terminais passam pelo
 * {@link JobTerminalTransitionService} — completar um job já terminal
 * (ex: cancelado) é ignorado.
 */
@Service
public class JobReporterUseCase implements JobReporter {

    private final JobRepositoryPortOut repository;
    private final JobResultStorePortOut resultStore;
    private final JobTerminalTransitionService terminal;
    private final JobEventPublisherPortOut events;

    public JobReporterUseCase(JobRepositoryPortOut repository, JobResultStorePortOut resultStore,
                              JobTerminalTransitionService terminal,
                              JobEventPublisherPortOut events) {
        this.repository = repository;
        this.resultStore = resultStore;
        this.terminal = terminal;
        this.events = events;
    }

    @Override
    public void progress(String jobId, int percent) {
        // transição restrita a PROCESSING — o evento nunca anuncia um estado
        // diferente do persistido
        if (repository.progress(jobId, percent)) {
            events.publish(new JobEvent(jobId, JobStatus.PROCESSING, percent));
        }
    }

    @Override
    public void append(String jobId, List<?> items) {
        resultStore.append(jobId, items);
    }

    @Override
    public void complete(String jobId) {
        terminal.complete(jobId);
    }

    @Override
    public void complete(String jobId, Object result) {
        terminal.complete(jobId, result);
    }

    @Override
    public void fail(String jobId, String title, String detail) {
        terminal.fail(jobId, title, detail);
    }
}
