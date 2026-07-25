package com.async.request.reply.core.service;

import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.port.out.JobProcessorPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Recupera jobs órfãos. O dispatch do processamento é in-process, então um job
 * cuja instância caiu (ou cujo dispatch foi recusado por saturação) ficaria
 * {@code PENDING} até o TTL, com o cliente fazendo polling eterno. Este serviço
 * varre o índice de ativos e:
 *
 * <ul>
 *   <li>{@code PENDING} parado há mais de {@code redispatch-after} → reenfileira
 *       (o {@code start} é um check-and-set atômico, então duas instâncias
 *       reenfileirando não processam em duplicidade);</li>
 *   <li>{@code PROCESSING} sem atualização há mais de {@code processing-timeout}
 *       → marca como falho (worker morreu sem reportar). Rotinas legitimamente
 *       longas devem reportar progresso para renovar o prazo;</li>
 *   <li>id indexado cujo job não existe mais (TTL) → remove do índice.</li>
 * </ul>
 */
public class StaleJobRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(StaleJobRecoveryService.class);

    private final JobRepositoryPortOut repository;
    private final JobProcessorPortOut processor;
    private final JobTransitionService transition;
    private final Clock clock;
    private final Duration redispatchAfter;
    private final Duration processingTimeout;
    private final int batchSize;

    public StaleJobRecoveryService(JobRepositoryPortOut repository,
                                   JobProcessorPortOut processor,
                                   JobTransitionService transition,
                                   AsyncJobsProperties properties,
                                   Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.transition = transition;
        this.clock = clock;
        this.redispatchAfter = properties.recovery().redispatchAfter();
        this.processingTimeout = properties.recovery().processingTimeout();
        this.batchSize = properties.recovery().batchSize();
    }

    /** @return quantos jobs sofreram alguma ação (reenfileirados, falhados ou purgados) */
    public int recover() {
        Instant now = clock.instant();
        // o menor dos dois prazos define quem é candidato; o filtro fino vem depois
        Duration earliest = redispatchAfter.compareTo(processingTimeout) <= 0
                ? redispatchAfter : processingTimeout;
        List<String> candidates = repository.findStaleActive(now.minus(earliest), batchSize);

        int acted = 0;
        for (String id : candidates) {
            Optional<Job> found = repository.findById(id);
            if (found.isEmpty()) {
                repository.untrackActive(id);
                log.debug("Job '{}' expirou mas seguia indexado; removido do indice de ativos", id);
                acted++;
                continue;
            }
            if (recover(found.get(), now)) {
                acted++;
            }
        }
        return acted;
    }

    private boolean recover(Job job, Instant now) {
        Duration idle = Duration.between(job.getLastUpdatedAt(), now);
        return switch (job.getStatus()) {
            case PENDING -> redispatchIfStale(job, idle);
            case PROCESSING -> failIfZombie(job, idle);
            case COMPLETED, FAILED, CANCELLED -> {
                repository.untrackActive(job.getId());
                log.debug("Job terminal '{}' seguia indexado; removido do indice de ativos", job.getId());
                yield true;
            }
        };
    }

    private boolean redispatchIfStale(Job job, Duration idle) {
        if (idle.compareTo(redispatchAfter) < 0 || !ownsType(job)) {
            return false;
        }
        log.info("Reenfileirando job orfao '{}' (type '{}') parado em PENDING por {}",
                job.getId(), job.getType(), idle);
        processor.process(job);
        return true;
    }

    private boolean failIfZombie(Job job, Duration idle) {
        if (idle.compareTo(processingTimeout) < 0 || !ownsType(job)) {
            return false;
        }
        log.warn("Job '{}' (type '{}') em PROCESSING sem atualizacao por {}; marcando como falho",
                job.getId(), job.getType(), idle);
        return transition.fail(job, "Processing timeout",
                "Nenhuma atualizacao por " + idle + "; o worker provavelmente morreu sem reportar.");
    }

    /**
     * Só age sobre jobs de types registrados nesta instância. Sem isso, um
     * deployment heterogêneo (ou duas aplicações no mesmo banco) reenfileiraria
     * jobs alheios e os mataria com "Unknown job type".
     */
    private boolean ownsType(Job job) {
        if (processor.supports(job.getType())) {
            return true;
        }
        log.debug("Job '{}' e do type '{}', nao registrado nesta instancia; deixando para quem o conhece",
                job.getId(), job.getType());
        return false;
    }
}
