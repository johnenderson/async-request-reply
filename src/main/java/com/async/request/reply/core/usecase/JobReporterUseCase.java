package com.async.request.reply.core.usecase;

import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.spi.JobReporter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementação do {@link JobReporter}. Os itens do resultado vão para o
 * result store (paginável); a conclusão é uma transição atômica no repositório
 * — completar um job já terminal (ex: cancelado) é ignorado.
 */
@Service
public class JobReporterUseCase implements JobReporter {

    private final JobRepositoryPortOut repository;
    private final JobResultStorePortOut resultStore;
    private final ReleaseSingleFlightUseCase releaseSingleFlight;
    private final JobEventPublisherPortOut events;

    public JobReporterUseCase(JobRepositoryPortOut repository, JobResultStorePortOut resultStore,
                              ReleaseSingleFlightUseCase releaseSingleFlight,
                              JobEventPublisherPortOut events) {
        this.repository = repository;
        this.resultStore = resultStore;
        this.releaseSingleFlight = releaseSingleFlight;
        this.events = events;
    }

    @Override
    public void progress(String jobId, int percent) {
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
        if (repository.complete(jobId)) {
            events.publish(new JobEvent(jobId, JobStatus.COMPLETED, 100));
        }
        releaseSingleFlight.release(jobId);
    }

    @Override
    public void complete(String jobId, Object result) {
        resultStore.append(jobId, asList(result));
        if (repository.complete(jobId)) {
            events.publish(new JobEvent(jobId, JobStatus.COMPLETED, 100));
        }
        releaseSingleFlight.release(jobId);
    }

    @Override
    public void fail(String jobId, String title, String detail) {
        if (repository.fail(jobId, title, detail)) {
            events.publish(new JobEvent(jobId, JobStatus.FAILED, null));
        }
        releaseSingleFlight.release(jobId);
    }

    /** Normaliza o resultado: null → vazio, List → cópia, valor único → lista de 1. */
    static List<Object> asList(Object result) {
        if (result == null) return List.of();
        if (result instanceof List<?> list) return new ArrayList<>(list);
        return List.of(result);
    }
}
