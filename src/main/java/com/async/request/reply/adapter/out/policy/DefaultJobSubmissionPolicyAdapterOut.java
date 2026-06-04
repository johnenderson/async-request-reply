package com.async.request.reply.adapter.out.policy;

import com.async.request.reply.autoconfigure.AsyncJobsProperties;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;

/**
 * Adapter out: política de submissão default, a partir de {@link AsyncJobsProperties}.
 */
public class DefaultJobSubmissionPolicyAdapterOut implements JobSubmissionPolicyPortOut {

    private final boolean coalesceInFlight;

    public DefaultJobSubmissionPolicyAdapterOut(AsyncJobsProperties properties) {
        this.coalesceInFlight = properties.coalesceInFlight();
    }

    @Override
    public boolean coalesceInFlight() {
        return coalesceInFlight;
    }
}
