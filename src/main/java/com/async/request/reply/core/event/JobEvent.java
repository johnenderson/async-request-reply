package com.async.request.reply.core.event;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;

/**
 * Evento de mudança de estado de um job, publicado a cada transição bem-sucedida
 * e consumido pelos assinantes (ex: streams SSE). Carrega apenas o essencial —
 * detalhes de falha e o resultado continuam nos endpoints de status/result.
 */
public record JobEvent(String jobId, JobStatus status, Integer percentComplete) {

    /** Snapshot do estado atual (enviado ao abrir uma assinatura). */
    public static JobEvent of(Job job) {
        return new JobEvent(job.getId(), job.getStatus(), job.getPercentComplete());
    }

    public boolean terminal() {
        return status == JobStatus.COMPLETED
                || status == JobStatus.FAILED
                || status == JobStatus.CANCELLED;
    }
}
