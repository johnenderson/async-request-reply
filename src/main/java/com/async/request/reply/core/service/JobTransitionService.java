package com.async.request.reply.core.service;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobEventPublisherPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobResultStorePortOut;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Ponto único das transições de estado de um job. Toda transição bem-sucedida
 * publica um {@link JobEvent} (e só quando o check-and-set atômico teve
 * sucesso); as terminais (complete/fail/cancel) ainda liberam o guard de
 * single-flight (compare-and-delete). Concentrar isso aqui impede que um novo
 * caminho esqueça de publicar o evento ou de liberar o guard.
 */
@Service
public class JobTransitionService {

    private final JobRepositoryPortOut repository;
    private final JobResultStorePortOut resultStore;
    private final JobEventPublisherPortOut events;
    private final SingleFlightPortOut singleFlight;
    private final CoalescingKeyPortOut coalescingKey;
    private final JobSubmissionPolicyPortOut submissionPolicy;
    private final Clock clock;

    public JobTransitionService(JobRepositoryPortOut repository,
                                JobResultStorePortOut resultStore,
                                JobEventPublisherPortOut events,
                                SingleFlightPortOut singleFlight,
                                CoalescingKeyPortOut coalescingKey,
                                JobSubmissionPolicyPortOut submissionPolicy,
                                Clock clock) {
        this.repository = repository;
        this.resultStore = resultStore;
        this.events = events;
        this.singleFlight = singleFlight;
        this.coalescingKey = coalescingKey;
        this.submissionPolicy = submissionPolicy;
        this.clock = clock;
    }

    // --- não-terminais -----------------------------------------------------

    /** PENDING → PROCESSING. @return {@code false} se o job já saiu de PENDING. */
    public boolean start(Job job) {
        if (!repository.start(job.getId())) {
            return false;
        }
        publish(job.getId(), JobStatus.PROCESSING, null);
        return true;
    }

    /** Atualiza o progresso enquanto PROCESSING (ignorado fora dele). */
    public void progress(String jobId, int percent) {
        if (repository.progress(jobId, percent)) {
            publish(jobId, JobStatus.PROCESSING, percent);
        }
    }

    // --- terminais ---------------------------------------------------------

    /** Materializa o resultado e conclui — o append precede a transição. */
    public void complete(Job job, Object result) {
        resultStore.append(job.getId(), asList(result));
        complete(job);
    }

    public void complete(Job job) {
        if (repository.complete(job.getId())) {
            publish(job.getId(), JobStatus.COMPLETED, 100);
            release(job);
        }
    }

    public void complete(String jobId, Object result) {
        resultStore.append(jobId, asList(result));
        complete(jobId);
    }

    public void complete(String jobId) {
        if (repository.complete(jobId)) {
            publish(jobId, JobStatus.COMPLETED, 100);
            release(jobId);
        }
    }

    public void fail(Job job, String title, String detail) {
        if (repository.fail(job.getId(), title, detail)) {
            publish(job.getId(), JobStatus.FAILED, null);
            release(job);
        }
    }

    public void fail(String jobId, String title, String detail) {
        if (repository.fail(jobId, title, detail)) {
            publish(jobId, JobStatus.FAILED, null);
            release(jobId);
        }
    }

    /** @return {@code false} se o job já estava em estado terminal. */
    public boolean cancel(Job job) {
        if (!repository.cancel(job.getId())) {
            return false;
        }
        publish(job.getId(), JobStatus.CANCELLED, null);
        release(job);
        return true;
    }

    // --- helpers -----------------------------------------------------------

    private void publish(String jobId, JobStatus status, Integer percent) {
        events.publish(new JobEvent(jobId, status, percent, clock.instant()));
    }

    /** Normaliza o resultado: null → vazio, List → cópia, valor único → lista de 1. */
    private static List<Object> asList(Object result) {
        if (result == null) return List.of();
        if (result instanceof List<?> list) return new ArrayList<>(list);
        return List.of(result);
    }

    private void release(Job job) {
        if (!submissionPolicy.coalesceInFlight()) {
            return;
        }
        singleFlight.release(coalescingKey.keyFor(job.getType()), job.getId());
    }

    private void release(String jobId) {
        if (!submissionPolicy.coalesceInFlight()) {
            return;
        }
        repository.findById(jobId).ifPresent(this::release);
    }
}
