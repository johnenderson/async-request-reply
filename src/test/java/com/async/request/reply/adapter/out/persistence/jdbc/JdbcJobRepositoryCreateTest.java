package com.async.request.reply.adapter.out.persistence.jdbc;

import com.async.request.reply.PostgresContainerTestSupport;
import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de {@code create} contra Postgres real: é aqui que o single-flight e a
 * idempotência deixam de ser lock distribuído e passam a ser constraint.
 */
@DisplayName("JdbcJobRepositoryAdapterOut.create")
class JdbcJobRepositoryCreateTest extends PostgresContainerTestSupport {

    private static final String TYPE = "relatorio";
    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");

    private final JdbcClient jdbc = JdbcClient.create(dataSource());
    private final JdbcJobRepositoryAdapterOut repository =
            new JdbcJobRepositoryAdapterOut(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void cleanTable() {
        jdbc.sql("delete from async_jobs").update();
    }

    @Test
    @DisplayName("cria job PENDING quando não há conflito")
    void create_should_insert_pending_job_when_there_is_no_conflict() {
        String id = UUID.randomUUID().toString();

        Job job = repository.create(id, TYPE, null, TYPE);

        assertThat(job.getId()).isEqualTo(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getCreatedAt()).isEqualTo(NOW);
        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("devolve o job existente quando a Idempotency-Key repete")
    void create_should_return_existing_job_when_idempotency_key_repeats() {
        String first = UUID.randomUUID().toString();
        repository.create(first, TYPE, "key-1", null);

        Job second = repository.create(UUID.randomUUID().toString(), TYPE, "key-1", null);

        assertThat(second.getId())
                .as("retry da mesma key nao cria segundo job")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("devolve o job ativo quando o escopo de coalescing já está tomado")
    void create_should_return_active_job_when_coalescing_scope_is_taken() {
        String active = UUID.randomUUID().toString();
        repository.create(active, TYPE, null, TYPE);

        Job second = repository.create(UUID.randomUUID().toString(), TYPE, null, TYPE);

        assertThat(second.getId())
                .as("nao pode haver dois jobs ativos do mesmo escopo")
                .isEqualTo(active);
    }

    @Test
    @DisplayName("cria novo job quando o anterior do mesmo escopo já é terminal")
    void create_should_insert_when_previous_job_in_the_scope_is_terminal() {
        String previous = UUID.randomUUID().toString();
        repository.create(previous, TYPE, null, TYPE);
        repository.complete(previous);

        String id = UUID.randomUUID().toString();
        Job job = repository.create(id, TYPE, null, TYPE);

        assertThat(job.getId())
                .as("o guard vale so enquanto o job esta ativo")
                .isEqualTo(id);
    }

    @Test
    @DisplayName("sem escopo de coalescing, submissões concorrentes criam jobs distintos")
    void create_should_insert_every_time_when_coalescing_scope_is_absent() {
        Job first = repository.create(UUID.randomUUID().toString(), TYPE, null, null);
        Job second = repository.create(UUID.randomUUID().toString(), TYPE, null, null);

        assertThat(second.getId()).isNotEqualTo(first.getId());
    }
}
