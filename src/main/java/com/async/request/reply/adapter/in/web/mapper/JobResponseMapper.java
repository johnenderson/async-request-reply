package com.async.request.reply.adapter.in.web.mapper;

import com.async.request.reply.adapter.in.web.dto.JobStatusResponse;
import com.async.request.reply.adapter.in.web.dto.SubmittedJobResponse;
import com.async.request.reply.core.result.JobStatusSnapshot;
import com.async.request.reply.core.result.SubmittedJob;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class JobResponseMapper {

    public SubmittedJobResponse toSubmittedJobResponse(SubmittedJob submitted, URI statusUri, URI eventsUri) {
        return new SubmittedJobResponse(submitted.jobId(), statusUri.toString(), eventsUri.toString());
    }

    public JobStatusResponse toStatusResponse(JobStatusSnapshot status) {
        return new JobStatusResponse(
                status.jobId(),
                status.status(),
                status.createdAt(),
                status.lastUpdatedAt(),
                status.percentComplete());
    }
}
