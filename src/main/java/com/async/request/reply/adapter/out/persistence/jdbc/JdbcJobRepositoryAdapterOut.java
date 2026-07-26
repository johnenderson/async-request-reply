package com.async.request.reply.adapter.out.persistence.jdbc;

import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.domain.JobFailure;
import com.async.request.reply.core.enums.JobStatus;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter out: persistência de jobs em Aurora PostgreSQL (ADR 0004).
 *
 * <p>Sem lock distribuído. O single-flight e a idempotência são <b>índices
 * únicos parciais</b>: a criação insere e deixa o banco recusar, em vez de
 * "checar e então criar". As transições são {@code UPDATE} condicional com
 * {@code RETURNING}, que devolve o instante gravado quando aplicou.</p>
 */
public class JdbcJobRepositoryAdapterOut implements JobRepositoryPortOut {

    private static final String INSERT = """
            insert into async_jobs
                (id, type, status, coalescing_key, idempotency_key, created_at, last_updated_at)
            values
                (:id, :type, 'PENDING', :coalescingKey, :idempotencyKey, :now, :now)
            on conflict do nothing
            """;

    private static final String SELECT_BY_ID = """
            select * from async_jobs where id = :id
            """;

    private static final String SELECT_BY_IDEMPOTENCY_KEY = """
            select * from async_jobs where idempotency_key = :idempotencyKey
            """;

    private static final String SELECT_ACTIVE_BY_COALESCING_KEY = """
            select * from async_jobs
            where coalescing_key = :coalescingKey and status in ('PENDING', 'PROCESSING')
            """;

    private static final String COMPLETE = """
            update async_jobs
               set status = 'COMPLETED', percent_complete = 100, last_updated_at = :now
             where id = :id and status in ('PENDING', 'PROCESSING')
            returning last_updated_at
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcJobRepositoryAdapterOut(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Job create(String id, String type, String idempotencyKey, String coalescingKey) {
        Instant now = clock.instant();
        if (insert(id, type, idempotencyKey, coalescingKey, now) == 1) {
            return Job.pending(id, type, now);
        }

        // conflito: já existe job para a idempotency key ou para o escopo ativo
        return owner(idempotencyKey, coalescingKey).orElseGet(() -> {
            // o dono terminou entre a recusa e a consulta; uma nova tentativa resolve
            Instant retryAt = clock.instant();
            if (insert(id, type, idempotencyKey, coalescingKey, retryAt) == 1) {
                return Job.pending(id, type, retryAt);
            }
            return owner(idempotencyKey, coalescingKey).orElseThrow(() -> new IllegalStateException(
                    "insercao do job '" + id + "' recusada sem dono identificavel"));
        });
    }

    private int insert(String id, String type, String idempotencyKey, String coalescingKey, Instant now) {
        return jdbc.sql(INSERT)
                .param("id", UUID.fromString(id))
                .param("type", type)
                .param("coalescingKey", coalescingKey)
                .param("idempotencyKey", idempotencyKey)
                .param("now", at(now))
                .update();
    }

    /** O job que bloqueou a inserção: por idempotência primeiro, depois por escopo ativo. */
    private Optional<Job> owner(String idempotencyKey, String coalescingKey) {
        if (idempotencyKey != null) {
            Optional<Job> byKey = findByIdempotencyKey(idempotencyKey);
            if (byKey.isPresent()) {
                return byKey;
            }
        }
        if (coalescingKey != null) {
            return jdbc.sql(SELECT_ACTIVE_BY_COALESCING_KEY)
                    .param("coalescingKey", coalescingKey)
                    .query(JOB_MAPPER)
                    .optional();
        }
        return Optional.empty();
    }

    @Override
    public Optional<Job> findById(String id) {
        return jdbc.sql(SELECT_BY_ID)
                .param("id", UUID.fromString(id))
                .query(JOB_MAPPER)
                .optional();
    }

    @Override
    public Optional<Job> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT_BY_IDEMPOTENCY_KEY)
                .param("idempotencyKey", idempotencyKey)
                .query(JOB_MAPPER)
                .optional();
    }

    @Override
    public Optional<Instant> complete(String id) {
        Instant now = clock.instant();
        return jdbc.sql(COMPLETE)
                .param("id", UUID.fromString(id))
                .param("now", at(now))
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    @Override
    public Optional<Instant> start(String id) {
        throw new UnsupportedOperationException("nao implementado");
    }

    @Override
    public Optional<Instant> fail(String id, String title, String detail) {
        throw new UnsupportedOperationException("nao implementado");
    }

    @Override
    public Optional<Instant> cancel(String id) {
        throw new UnsupportedOperationException("nao implementado");
    }

    @Override
    public Optional<Instant> progress(String id, int percent) {
        throw new UnsupportedOperationException("nao implementado");
    }

    @Override
    public List<String> findStaleActive(Instant olderThan, int limit) {
        throw new UnsupportedOperationException("nao implementado");
    }

    @Override
    public void untrackActive(String id) {
        // Não há índice separado no storage relacional: "ativo" é um predicado na
        // própria tabela, então não existe entrada órfã a remover.
    }

    // --- mapeamento ---------------------------------------------------------

    private static final RowMapper<Job> JOB_MAPPER = (rs, rowNum) -> Job.restore(
            rs.getString("id"),
            rs.getString("type"),
            JobStatus.valueOf(rs.getString("status")),
            instant(rs, "created_at"),
            instant(rs, "last_updated_at"),
            failure(rs),
            rs.getObject("percent_complete", Integer.class));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static JobFailure failure(ResultSet rs) throws SQLException {
        String title = rs.getString("error_title");
        return (title == null || title.isBlank()) ? null : new JobFailure(title, rs.getString("error_detail"));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
