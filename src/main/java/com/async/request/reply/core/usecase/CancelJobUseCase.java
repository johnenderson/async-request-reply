package com.async.request.reply.core.usecase;

import com.async.request.reply.core.enums.CancelResult;
import com.async.request.reply.core.port.in.CancelJobPortIn;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.springframework.stereotype.Service;

/**
 * Implementação do {@link CancelJobPortIn}.
 */
@Service
public class CancelJobUseCase implements CancelJobPortIn {

    private final JobRepositoryPortOut repository;

    public CancelJobUseCase(JobRepositoryPortOut repository) {
        this.repository = repository;
    }

    @Override
    public CancelResult execute(String id) {
        if (repository.findById(id).isEmpty()) {
            return CancelResult.NOT_FOUND;
        }
        // transição atômica; false = já terminal
        return repository.cancel(id) ? CancelResult.CANCELLED : CancelResult.ALREADY_TERMINAL;
    }
}
