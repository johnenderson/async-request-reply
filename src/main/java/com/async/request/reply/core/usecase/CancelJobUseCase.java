package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.CancelResult;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.in.CancelJobPortIn;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Implementação do {@link CancelJobPortIn}.
 */
@Service
public class CancelJobUseCase implements CancelJobPortIn {

    private final JobRepositoryPortOut repository;
    private final ReleaseSingleFlightUseCase releaseSingleFlight;
    private final JobEventPublisherPortOut events;

    public CancelJobUseCase(JobRepositoryPortOut repository,
                            ReleaseSingleFlightUseCase releaseSingleFlight,
                            JobEventPublisherPortOut events) {
        this.repository = repository;
        this.releaseSingleFlight = releaseSingleFlight;
        this.events = events;
    }

    @Override
    public CancelResult execute(String id) {
        Optional<Job> job = repository.findById(id);
        if (job.isEmpty()) {
            return CancelResult.NOT_FOUND;
        }
        // transição atômica; false = já terminal
        if (!repository.cancel(id)) {
            return CancelResult.ALREADY_TERMINAL;
        }
        events.publish(new JobEvent(id, JobStatus.CANCELLED, null));
        releaseSingleFlight.release(job.get());
        return CancelResult.CANCELLED;
    }
}
