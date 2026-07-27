package com.async.request.reply;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.service.StaleJobRecoveryService;
import com.async.request.reply.spi.JobHandler;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recuperação de jobs órfãos: sem ela, um job cuja instância caiu antes de
 * processar ficaria PENDING para sempre e o cliente faria polling eterno.
 *
 * <p>Os órfãos são escritos direto na tabela (simulando o estado deixado por uma
 * instância que morreu) e a varredura é disparada manualmente, sem esperar o
 * intervalo do agendador.</p>
 */
@SpringBootTest(properties = "async-jobs.coalesce-in-flight=false")
@DisplayName("Recuperação de jobs órfãos")
class StaleJobRecoveryTest extends PostgresContainerTestSupport {

    private static final String TYPE = "reap-test";

    @Autowired
    StaleJobRecoveryService recovery;

    @Autowired
    JobRepositoryPortOut repository;

    private final JdbcClient jdbc = JdbcClient.create(dataSource());

    @BeforeEach
    void cleanTable() {
        truncateJobs();
    }

    @TestConfiguration
    static class Handlers {
        @Bean
        JobHandler reapHandler() {
            return new JobHandler() {
                public String type() { return TYPE; }
                public void handle() { }
            };
        }
    }

    @Test
    @DisplayName("job PENDING órfão é reenfileirado e conclui")
    void orphanedPendingJobIsRedispatchedAndCompletes() {
        String jobId = writeOrphan(JobStatus.PENDING, TYPE, Instant.now().minus(Duration.ofHours(2)));

        assertThat(recovery.recover()).isPositive();

        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> status(jobId) == JobStatus.COMPLETED);
    }

    @Test
    @DisplayName("job zumbi em PROCESSING é marcado como falho após o timeout")
    void zombieProcessingJobIsFailedAfterTimeout() {
        String jobId = writeOrphan(JobStatus.PROCESSING, TYPE, Instant.now().minus(Duration.ofHours(2)));

        assertThat(recovery.recover()).isPositive();

        assertThat(status(jobId)).isEqualTo(JobStatus.FAILED);
        assertThat(repository.findById(jobId).orElseThrow().getFailure().title())
                .isEqualTo("Processing timeout");
    }

    @Test
    @DisplayName("job recente é deixado em paz")
    void recentJobsAreLeftAlone() {
        String jobId = writeOrphan(JobStatus.PENDING, TYPE, Instant.now());

        recovery.recover();

        assertThat(status(jobId)).isEqualTo(JobStatus.PENDING);
    }

    /**
     * Duas aplicações no mesmo banco, ou um deployment heterogêneo: a varredura
     * não pode reenfileirar (nem matar) job de type que esta instância não conhece.
     */
    @Test
    @DisplayName("job de type não registrado nesta instância é deixado para quem o conhece")
    void jobOfUnknownTypeIsLeftAlone() {
        String jobId = writeOrphan(JobStatus.PROCESSING, "type-de-outra-app",
                Instant.now().minus(Duration.ofHours(2)));

        recovery.recover();

        assertThat(status(jobId)).isEqualTo(JobStatus.PROCESSING);
    }

    // --- helpers -------------------------------------------------------------

    /** Escreve um job direto na tabela, como o deixado por uma instância que caiu. */
    private String writeOrphan(JobStatus status, String type, Instant lastUpdatedAt) {
        String jobId = UUID.randomUUID().toString();
        jdbc.sql("""
                        insert into async_jobs (id, type, status, created_at, last_updated_at)
                        values (:id, :type, :status, :at, :at)
                        """)
                .param("id", UUID.fromString(jobId))
                .param("type", type)
                .param("status", status.name())
                .param("at", lastUpdatedAt.atOffset(ZoneOffset.UTC))
                .update();
        return jobId;
    }

    private JobStatus status(String jobId) {
        return repository.findById(jobId).map(Job::getStatus).orElse(null);
    }
}
