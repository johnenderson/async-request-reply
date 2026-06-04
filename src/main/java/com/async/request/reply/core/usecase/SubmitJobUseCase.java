package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.exception.InvalidJobRequestException;
import com.async.request.reply.core.port.in.SubmitJobPortIn;
import com.async.request.reply.core.port.in.SubmittedJob;
import com.async.request.reply.core.port.out.JobPolicyPortOut;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Implementação do {@link SubmitJobPortIn}.
 * Responsável pela request validation e pela decisão de enfileirar o processamento.
 */
@Service
public class SubmitJobUseCase implements SubmitJobPortIn {

    private final JobRepositoryPortOut repository;
    private final JobProcessorPortOut processor;
    private final JobPolicyPortOut policy;

    public SubmitJobUseCase(JobRepositoryPortOut repository, JobProcessorPortOut processor, JobPolicyPortOut policy) {
        this.repository = repository;
        this.processor = processor;
        this.policy = policy;
    }

    @Override
    public SubmittedJob execute(Map<String, Object> payload, String idempotencyKey) {
        validate(payload);
        Job job = repository.save(payload, idempotencyKey);
        // Idempotent: só inicia o processamento para um job recém-criado
        if (job.getStatus() == JobStatus.PENDING) {
            processor.process(job); // via Spring proxy → @Async funciona
        }
        return new SubmittedJob(job.getId(), policy.retryAfterSeconds());
    }

    private void validate(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            throw new InvalidJobRequestException("Request body must not be empty.");
        }
    }
}
