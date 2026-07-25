package com.async.request.reply.core.event;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;

import java.time.Instant;

/**
 * Evento de mudança de estado de um job, publicado a cada transição bem-sucedida
 * e consumido pelos assinantes (ex: streams SSE). Carrega apenas o essencial —
 * detalhes de falha e o resultado continuam nos endpoints de status/result.
 *
 * <p>{@code lastUpdatedAt} permite ao cliente ordenar/deduplicar: como o
 * snapshot inicial e os eventos do pub/sub podem chegar fora de ordem numa
 * corrida, o cliente mantém o de maior timestamp e descarta os mais antigos.</p>
 */
public record JobEvent(String jobId, JobStatus status, Integer percentComplete, Instant lastUpdatedAt) {

    /** Snapshot do estado atual (enviado ao abrir uma assinatura). */
    public static JobEvent of(Job job) {
        return new JobEvent(job.getId(), job.getStatus(), job.getPercentComplete(), job.getLastUpdatedAt());
    }

    public boolean terminal() {
        return status == JobStatus.COMPLETED
                || status == JobStatus.FAILED
                || status == JobStatus.CANCELLED;
    }
}
