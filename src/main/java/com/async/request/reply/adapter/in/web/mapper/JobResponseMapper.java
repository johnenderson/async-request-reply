package com.async.request.reply.adapter.in.web.mapper;

import com.async.request.reply.adapter.in.web.dto.JobNotCompletedResponse;
import com.async.request.reply.adapter.in.web.dto.JobResultResponse;
import com.async.request.reply.adapter.in.web.dto.JobStatusResponse;
import com.async.request.reply.adapter.in.web.dto.SubmittedJobResponse;
import com.async.request.reply.core.result.JobResultPage;
import com.async.request.reply.core.result.JobResultView;
import com.async.request.reply.core.result.JobStatusSnapshot;
import com.async.request.reply.core.result.JobStatusView;
import com.async.request.reply.core.result.SubmittedJob;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class JobResponseMapper {

    public SubmittedJobResponse toSubmittedJobResponse(SubmittedJob submitted, URI statusUri) {
        return new SubmittedJobResponse(submitted.jobId(), statusUri.toString());
    }

    public JobStatusResponse toStatusResponse(JobStatusView.InProgress inProgress) {
        JobStatusSnapshot status = inProgress.body();
        return new JobStatusResponse(
                status.jobId(),
                status.status(),
                status.createdAt(),
                status.lastUpdatedAt(),
                status.percentComplete());
    }

    public JobResultResponse toResultResponse(JobResultView.Found found) {
        JobResultPage page = found.page();
        return new JobResultResponse(
                found.jobId(),
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }

    public JobNotCompletedResponse toNotCompletedResponse(JobResultView.NotCompleted notCompleted) {
        return new JobNotCompletedResponse("Job is not completed yet", notCompleted.status());
    }
}
