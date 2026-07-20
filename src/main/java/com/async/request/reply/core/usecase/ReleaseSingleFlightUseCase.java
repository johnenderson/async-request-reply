package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.port.out.JobSubmissionPolicyPortOut;
import com.async.request.reply.core.port.out.SingleFlightPortOut;
import org.springframework.stereotype.Service;

/**
 * Libera o guard de single-flight assim que o job atinge estado terminal
 * (complete/fail/cancel), em vez de esperar o TTL. O release é um
 * compare-and-delete: só remove se o guard ainda apontar para o job, então
 * nunca apaga o claim de um job mais novo.
 */
@Service
public class ReleaseSingleFlightUseCase {

    private final JobRepositoryPortOut repository;
    private final SingleFlightPortOut singleFlight;
    private final CoalescingKeyPortOut coalescingKey;
    private final JobSubmissionPolicyPortOut submissionPolicy;

    public ReleaseSingleFlightUseCase(JobRepositoryPortOut repository,
                                      SingleFlightPortOut singleFlight,
                                      CoalescingKeyPortOut coalescingKey,
                                      JobSubmissionPolicyPortOut submissionPolicy) {
        this.repository = repository;
        this.singleFlight = singleFlight;
        this.coalescingKey = coalescingKey;
        this.submissionPolicy = submissionPolicy;
    }

    /** Variante para quem já tem o {@link Job} em mãos (evita lookup extra). */
    public void release(Job job) {
        if (!submissionPolicy.coalesceInFlight()) {
            return;
        }
        singleFlight.release(coalescingKey.keyFor(job.getType()), job.getId());
    }

    /** Variante por id: busca o job para descobrir o {@code type} da chave. */
    public void release(String jobId) {
        if (!submissionPolicy.coalesceInFlight()) {
            return;
        }
        repository.findById(jobId).ifPresent(job ->
                singleFlight.release(coalescingKey.keyFor(job.getType()), job.getId()));
    }
}
