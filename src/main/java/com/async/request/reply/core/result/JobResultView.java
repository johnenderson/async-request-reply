package com.async.request.reply.core.result;

import com.async.request.reply.core.enums.JobStatus;

/**
 * Domain outcome da recuperação do resultado de um job.
 */
public sealed interface JobResultView {

    /** O job foi concluído e o resultado (paginado) está disponível. */
    record Found(String jobId, JobResultPage page) implements JobResultView {}

    /** O job existe mas ainda não foi concluído. */
    record NotCompleted(JobStatus status) implements JobResultView {}

    /** Nenhum job existe para o id informado. */
    record NotFound() implements JobResultView {}
}
