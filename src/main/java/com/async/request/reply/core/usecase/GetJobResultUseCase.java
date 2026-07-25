package com.async.request.reply.core.usecase;

import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.in.GetJobResultPortIn;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.result.JobResultView;

/**
 * Implementação do {@link GetJobResultPortIn}. A página vem direto do result
 * store (paginação nativa), e não da carga do resultado inteiro em memória.
 */
public class GetJobResultUseCase implements GetJobResultPortIn {

    private final JobRepositoryPortOut repository;
    private final JobResultStorePortOut resultStore;

    public GetJobResultUseCase(JobRepositoryPortOut repository, JobResultStorePortOut resultStore) {
        this.repository = repository;
        this.resultStore = resultStore;
    }

    @Override
    public JobResultView execute(String id, int page, int size) {
        return repository.findById(id)
                .map(job -> job.getStatus() == JobStatus.COMPLETED
                        ? (JobResultView) new JobResultView.Found(job.getId(), resultStore.page(id, page, size))
                        : new JobResultView.NotCompleted(job.getStatus()))
                .orElseGet(JobResultView.NotFound::new);
    }
}
