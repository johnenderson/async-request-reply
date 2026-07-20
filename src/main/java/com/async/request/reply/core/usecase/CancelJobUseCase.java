package com.async.request.reply.core.usecase;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.CancelResult;
import com.async.request.reply.core.port.in.CancelJobPortIn;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.service.JobTerminalTransitionService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Implementação do {@link CancelJobPortIn}.
 */
@Service
public class CancelJobUseCase implements CancelJobPortIn {

    private final JobRepositoryPortOut repository;
    private final JobTerminalTransitionService terminal;

    public CancelJobUseCase(JobRepositoryPortOut repository,
                            JobTerminalTransitionService terminal) {
        this.repository = repository;
        this.terminal = terminal;
    }

    @Override
    public CancelResult execute(String id) {
        Optional<Job> job = repository.findById(id);
        if (job.isEmpty()) {
            return CancelResult.NOT_FOUND;
        }
        // transição atômica; false = já terminal
        return terminal.cancel(job.get()) ? CancelResult.CANCELLED : CancelResult.ALREADY_TERMINAL;
    }
}
