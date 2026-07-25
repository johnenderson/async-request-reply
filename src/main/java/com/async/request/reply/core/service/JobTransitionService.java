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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ponto único das transições de estado de um job. Toda transição bem-sucedida
 * publica um {@link JobEvent} — com o <b>mesmo instante que foi persistido</b>,
 * não um novo relógio — e as terminais liberam o guard de single-flight
 * (compare-and-delete). Concentrar isso aqui impede que um novo caminho esqueça
 * de publicar o evento ou de liberar o guard.
 *
 * <p>Todos os métodos devolvem se a transição foi aplicada: {@code false}
 * significa que o job não existe mais ou já estava em estado terminal.</p>
 */
public class JobTransitionService {

    /** Um job FAILED sempre precisa de título — nem a SPI pode gravar em branco. */
    private static final String DEFAULT_FAILURE_TITLE = "Job failed";

    private final JobRepositoryPortOut repository;
    private final JobResultStorePortOut resultStore;
    private final JobEventPublisherPortOut events;
    private final SingleFlightPortOut singleFlight;
    private final CoalescingKeyPortOut coalescingKey;
    private final JobSubmissionPolicyPortOut submissionPolicy;

    public JobTransitionService(JobRepositoryPortOut repository,
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

    // --- não-terminais -----------------------------------------------------

    /** PENDING → PROCESSING. */
    public boolean start(Job job) {
        return repository.start(job.getId())
                .map(at -> publish(job.getId(), JobStatus.PROCESSING, null, at))
                .orElse(false);
    }

    /** Atualiza o progresso enquanto PROCESSING (ignorado fora dele). */
    public boolean progress(String jobId, int percent) {
        return repository.progress(jobId, percent)
                .map(at -> publish(jobId, JobStatus.PROCESSING, percent, at))
                .orElse(false);
    }

    // --- terminais ---------------------------------------------------------

    /** Materializa o resultado e conclui — o append precede a transição. */
    public boolean complete(Job job, Object result) {
        resultStore.append(job.getId(), asList(result));
        return complete(job);
    }

    public boolean complete(Job job) {
        return completeById(job.getId(), job);
    }

    public boolean complete(String jobId, Object result) {
        resultStore.append(jobId, asList(result));
        return complete(jobId);
    }

    public boolean complete(String jobId) {
        return completeById(jobId, null);
    }

    public boolean fail(Job job, String title, String detail) {
        return failById(job.getId(), job, title, detail);
    }

    public boolean fail(String jobId, String title, String detail) {
        return failById(jobId, null, title, detail);
    }

    public boolean cancel(Job job) {
        Optional<Instant> at = repository.cancel(job.getId());
        if (at.isEmpty()) {
            return false;
        }
        publish(job.getId(), JobStatus.CANCELLED, null, at.get());
        release(job);
        return true;
    }

    // --- helpers -----------------------------------------------------------

    private boolean completeById(String jobId, Job known) {
        Optional<Instant> at = repository.complete(jobId);
        if (at.isEmpty()) {
            return false;
        }
        publish(jobId, JobStatus.COMPLETED, 100, at.get());
        release(jobId, known);
        return true;
    }

    private boolean failById(String jobId, Job known, String title, String detail) {
        Optional<Instant> at = repository.fail(jobId, normalizeTitle(title), detail);
        if (at.isEmpty()) {
            return false;
        }
        publish(jobId, JobStatus.FAILED, null, at.get());
        release(jobId, known);
        return true;
    }

    private static String normalizeTitle(String title) {
        return (title == null || title.isBlank()) ? DEFAULT_FAILURE_TITLE : title;
    }

    private boolean publish(String jobId, JobStatus status, Integer percent, Instant at) {
        events.publish(new JobEvent(jobId, status, percent, at));
        return true;
    }

    /** Normaliza o resultado: null → vazio, List → cópia, valor único → lista de 1. */
    private static List<Object> asList(Object result) {
        if (result == null) return List.of();
        if (result instanceof List<?> list) return new ArrayList<>(list);
        return List.of(result);
    }

    /** Libera o guard usando o job em mãos, ou buscando-o só se necessário. */
    private void release(String jobId, Job known) {
        if (!submissionPolicy.coalesceInFlight()) {
            return;
        }
        if (known != null) {
            release(known);
            return;
        }
        repository.findById(jobId).ifPresent(this::release);
    }

    private void release(Job job) {
        if (!submissionPolicy.coalesceInFlight()) {
            return;
        }
        singleFlight.release(coalescingKey.keyFor(job.getType()), job.getId());
    }
}
