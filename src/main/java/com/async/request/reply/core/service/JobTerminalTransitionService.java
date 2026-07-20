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

import java.util.ArrayList;
import java.util.List;

/**
 * Ponto único das transições terminais (complete/fail/cancel). Toda transição
 * terminal tem TRÊS efeitos que precisam andar juntos, e só quando a transição
 * atômica teve sucesso:
 *
 * <ol>
 *   <li>a transição de estado no repositório (check-and-set);</li>
 *   <li>a publicação do {@link JobEvent} (streams SSE etc.);</li>
 *   <li>a liberação do guard de single-flight (compare-and-delete).</li>
 * </ol>
 *
 * Concentrar a invariante aqui impede que um novo caminho terminal esqueça um
 * dos efeitos. As variantes com {@link Job} evitam lookup extra; as variantes
 * por id buscam o job apenas quando o coalescing está habilitado.
 */
@Service
public class JobTerminalTransitionService {

    private final JobRepositoryPortOut repository;
    private final JobResultStorePortOut resultStore;
    private final JobEventPublisherPortOut events;
    private final SingleFlightPortOut singleFlight;
    private final CoalescingKeyPortOut coalescingKey;
    private final JobSubmissionPolicyPortOut submissionPolicy;

    public JobTerminalTransitionService(JobRepositoryPortOut repository,
                                        JobResultStorePortOut resultStore,
                                        JobEventPublisherPortOut events,
                                        SingleFlightPortOut singleFlight,
                                        CoalescingKeyPortOut coalescingKey,
                                        JobSubmissionPolicyPortOut submissionPolicy) {
        this.repository = repository;
        this.resultStore = resultStore;
        this.events = events;
        this.singleFlight = singleFlight;
        this.coalescingKey = coalescingKey;
        this.submissionPolicy = submissionPolicy;
    }

    /** Materializa o resultado e conclui — o append precede a transição. */
    public void complete(Job job, Object result) {
        resultStore.append(job.getId(), asList(result));
        complete(job);
    }

    public void complete(Job job) {
        if (repository.complete(job.getId())) {
            events.publish(new JobEvent(job.getId(), JobStatus.COMPLETED, 100));
            release(job);
        }
    }

    public void complete(String jobId, Object result) {
        resultStore.append(jobId, asList(result));
        complete(jobId);
    }

    public void complete(String jobId) {
        if (repository.complete(jobId)) {
            events.publish(new JobEvent(jobId, JobStatus.COMPLETED, 100));
            release(jobId);
        }
    }

    public void fail(Job job, String title, String detail) {
        if (repository.fail(job.getId(), title, detail)) {
            events.publish(new JobEvent(job.getId(), JobStatus.FAILED, null));
            release(job);
        }
    }

    public void fail(String jobId, String title, String detail) {
        if (repository.fail(jobId, title, detail)) {
            events.publish(new JobEvent(jobId, JobStatus.FAILED, null));
            release(jobId);
        }
    }

    /** @return {@code false} se o job já estava em estado terminal. */
    public boolean cancel(Job job) {
        if (!repository.cancel(job.getId())) {
            return false;
        }
        events.publish(new JobEvent(job.getId(), JobStatus.CANCELLED, null));
        release(job);
        return true;
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
