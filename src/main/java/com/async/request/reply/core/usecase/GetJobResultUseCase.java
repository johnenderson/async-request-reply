package com.async.request.reply.core.usecase;

import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.in.GetJobResultPortIn;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.result.JobResultPage;
import com.async.request.reply.core.result.JobResultView;
import org.springframework.stereotype.Service;

/**
 * Implementação do {@link GetJobResultPortIn}.
 */
@Service
public class GetJobResultUseCase implements GetJobResultPortIn {

    private final JobRepositoryPortOut repository;

    public GetJobResultUseCase(JobRepositoryPortOut repository) {
        this.repository = repository;
    }

    @Override
    public JobResultView execute(String id, int page, int size) {
        return repository.findById(id)
                .map(job -> job.getStatus() == JobStatus.COMPLETED
                        ? (JobResultView) new JobResultView.Found(
                                job.getId(), JobResultPage.of(job.getResult(), page, size))
                        : new JobResultView.NotCompleted(job.getStatus()))
                .orElseGet(JobResultView.NotFound::new);
    }
}
