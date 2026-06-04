package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.port.in.GetJobStatusPortIn;
import com.async.request.reply.core.port.in.JobStatusResponse;
import com.async.request.reply.core.port.in.JobStatusView;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.springframework.stereotype.Service;

/**
 * Implementação do {@link GetJobStatusPortIn}.
 * Encapsula a decisão status → outcome, livre de HTTP concerns.
 */
@Service
public class GetJobStatusUseCase implements GetJobStatusPortIn {

    private final JobRepositoryPortOut repository;
    private final JobPolicyPortOut policy;

    public GetJobStatusUseCase(JobRepositoryPortOut repository, JobPolicyPortOut policy) {
        this.repository = repository;
        this.policy = policy;
    }

    @Override
    public JobStatusView execute(String id) {
        return repository.findById(id)
                .map(this::toView)
                .orElseGet(JobStatusView.NotFound::new);
    }

    private JobStatusView toView(Job job) {
        return switch (job.getStatus()) {
            case PENDING, PROCESSING -> new JobStatusView.InProgress(
                    JobStatusResponse.from(job), policy.retryAfterSeconds(), policy.expiresAt(job));
            case COMPLETED -> new JobStatusView.Completed(policy.expiresAt(job));
            case FAILED    -> new JobStatusView.Failed(job.getFailure());
            case CANCELLED -> new JobStatusView.Cancelled();
        };
    }
}
