package com.async.request.reply;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.service.StaleJobRecoveryService;
import com.async.request.reply.spi.JobHandler;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recuperação de jobs órfãos: sem ela, um job cuja instância caiu antes de
 * processar ficaria PENDING até o TTL e o cliente faria polling eterno.
 *
 * <p>Os jobs órfãos são escritos direto no Valkey (simulando o estado deixado
 * por uma instância que morreu) e a varredura é disparada manualmente, sem
 * esperar o intervalo do agendador.</p>
 */
@SpringBootTest(properties = "async-jobs.coalesce-in-flight=false")
class StaleJobRecoveryTest extends ValkeyContainerTestSupport {

    @Autowired
    StaleJobRecoveryService recovery;

    @Autowired
    JobRepositoryPortOut repository;

    @Autowired
    RedissonClient redisson;

    @TestConfiguration
    static class Handlers {
        @Bean
        JobHandler<List<String>> reapHandler() {
            return new JobHandler<>() {
                public String type() { return "reap-test"; }
                public List<String> handle() { return List.of("recuperado"); }
            };
        }
    }

    @Test
    void orphanedPendingJobIsRedispatchedAndCompletes() {
        String jobId = writeOrphan(JobStatus.PENDING, Instant.now().minus(Duration.ofHours(2)));

        assertThat(recovery.recover()).isPositive();

        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> status(jobId) == JobStatus.COMPLETED);
    }

    @Test
    void zombieProcessingJobIsFailedAfterTimeout() {
        String jobId = writeOrphan(JobStatus.PROCESSING, Instant.now().minus(Duration.ofHours(2)));

        assertThat(recovery.recover()).isPositive();

        assertThat(status(jobId)).isEqualTo(JobStatus.FAILED);
        assertThat(repository.findById(jobId).orElseThrow().getFailure().title())
                .isEqualTo("Processing timeout");
    }

    @Test
    void recentJobsAreLeftAlone() {
        String jobId = writeOrphan(JobStatus.PENDING, Instant.now());

        recovery.recover();

        assertThat(status(jobId)).isEqualTo(JobStatus.PENDING);
    }

    /** Id indexado cujo job já expirou deve sair do índice, não repetir para sempre. */
    @Test
    void indexedButExpiredJobIsPurgedFromIndex() {
        String jobId = UUID.randomUUID().toString();
        long oldScore = Instant.now().minus(Duration.ofHours(2)).toEpochMilli();
        redisson.getScoredSortedSet("jobs:active", StringCodec.INSTANCE).add(oldScore, jobId);

        assertThat(recovery.recover()).isPositive();

        assertThat(repository.findStaleActive(Instant.now(), 100)).doesNotContain(jobId);
    }

    // --- helpers -------------------------------------------------------------

    /** Escreve um job direto no storage, como o deixado por uma instância que caiu. */
    private String writeOrphan(JobStatus status, Instant lastUpdatedAt) {
        String jobId = UUID.randomUUID().toString();
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("type", "reap-test");
        hash.put("status", status.name());
        hash.put("createdAt", lastUpdatedAt.toString());
        hash.put("lastUpdatedAt", lastUpdatedAt.toString());

        redisson.getMap("job:" + jobId, StringCodec.INSTANCE).putAll(hash);
        redisson.getScoredSortedSet("jobs:active", StringCodec.INSTANCE)
                .add(lastUpdatedAt.toEpochMilli(), jobId);
        return jobId;
    }

    private JobStatus status(String jobId) {
        return repository.findById(jobId).map(Job::getStatus).orElse(null);
    }
}
