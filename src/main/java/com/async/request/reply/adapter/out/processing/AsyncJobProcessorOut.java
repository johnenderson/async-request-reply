package com.async.request.reply.adapter.out.processing;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.exception.JobFailedException;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Adapter out: implementação do {@link JobProcessorPortOut} baseada em @Async.
 * Manter o @Async neste bean dedicado garante que o AOP proxy do Spring
 * seja usado em toda chamada — self-invocation no mesmo bean ignora o proxy.
 */
@Component
public class AsyncJobProcessorOut implements JobProcessorPortOut {

    @Override
    @Async
    public void process(Job job) {
        if (!job.transitionTo(JobStatus.PENDING, JobStatus.PROCESSING)) {
            return;
        }
        try {
            for (int i = 20; i <= 80; i += 20) {
                if (job.getStatus() == JobStatus.CANCELLED) return;
                Thread.sleep(1_000);
                job.updateProgress(i);
            }
            if (job.getStatus() == JobStatus.CANCELLED) return;
            Thread.sleep(1_000);
            job.complete("Processed: " + job.getPayload());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.fail(new JobFailedException("Processing interrupted",
                    "The job was interrupted before completion."));
        } catch (Exception e) {
            job.fail(new JobFailedException("Processing error", e.getMessage()));
        }
    }
}
