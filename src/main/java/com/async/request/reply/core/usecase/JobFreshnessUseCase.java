package com.async.request.reply.core.usecase;

import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobFreshnessPolicyPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.spi.JobFreshness;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Implementação do {@link JobFreshness}. Responde sobre o mesmo escopo que o
 * {@code SubmitJobUseCase} usa para decidir se poupa uma carga — a chave de
 * coalescing do {@code type} — para que a resposta de domínio e a decisão da lib
 * nunca discordem sobre o que está quente.
 */
public class JobFreshnessUseCase implements JobFreshness {

    private final JobRepositoryPortOut repository;
    private final JobFreshnessPolicyPortOut policy;
    private final CoalescingKeyPortOut coalescingKey;
    private final Clock clock;

    public JobFreshnessUseCase(JobRepositoryPortOut repository,
                               JobFreshnessPolicyPortOut policy,
                               CoalescingKeyPortOut coalescingKey,
                               Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.coalescingKey = coalescingKey;
        this.clock = clock;
    }

    @Override
    public Optional<Instant> lastRefreshedAt(String type) {
        return repository.findLastCompletedAt(coalescingKey.keyFor(type));
    }

    @Override
    public boolean isFresh(String type) {
        Optional<Instant> lastRefreshedAt = lastRefreshedAt(type);
        if (lastRefreshedAt.isEmpty()) {
            return false;
        }
        // sem janela nada e quente — a mesma razao pela qual toda submissao
        // dispara carga; prometer frescura aqui seria mentir para o cliente
        return policy.freshnessFor(type)
                .map(window -> lastRefreshedAt.get().isAfter(clock.instant().minus(window)))
                .orElse(false);
    }
}
