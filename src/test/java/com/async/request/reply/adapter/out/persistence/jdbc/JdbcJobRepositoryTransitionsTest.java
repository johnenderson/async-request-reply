package com.async.request.reply.adapter.out.persistence.jdbc;

import com.async.request.reply.PostgresContainerTestSupport;
import com.async.request.reply.core.domain.Job;
import com.async.request.reply.core.enums.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato das transições contra Postgres real: cada uma é um {@code UPDATE}
 * condicional com {@code RETURNING}, que devolve o instante gravado quando
 * aplicou e vazio quando não era permitida — sem lock.
 */
@DisplayName("JdbcJobRepositoryAdapterOut: transições e consultas")
class JdbcJobRepositoryTransitionsTest extends PostgresContainerTestSupport {

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
    @DisplayName("start devolve o instante gravado e leva PENDING para PROCESSING")
    void start_should_return_persisted_instant_and_move_to_processing() {
        String id = newJob();

        Optional<Instant> at = repository.start(id);

        assertThat(at).contains(NOW);
        assertThat(status(id)).isEqualTo(JobStatus.PROCESSING);
    }

    @Test
    @DisplayName("start é recusado quando o job já saiu de PENDING")
    void start_should_be_refused_when_job_is_not_pending() {
        String id = newJob();
        repository.start(id);

        assertThat(repository.start(id)).isEmpty();
    }

    @ParameterizedTest(name = "de {0}")
    @DisplayName("complete aplica a partir de qualquer estado ativo")
    @EnumSource(value = JobStatus.class, names = {"PENDING", "PROCESSING"})
    void complete_should_apply_from_active_states(JobStatus from) {
        String id = newJob();
        if (from == JobStatus.PROCESSING) {
            repository.start(id);
        }

        assertThat(repository.complete(id)).contains(NOW);
        assertThat(status(id)).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    @DisplayName("complete é recusado quando o job já é terminal — cancelamento não é sobrescrito")
    void complete_should_be_refused_when_job_is_already_terminal() {
        String id = newJob();
        repository.cancel(id);

        assertThat(repository.complete(id)).isEmpty();
        assertThat(status(id)).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    @DisplayName("fail grava titulo e detalhe da falha")
    void fail_should_persist_title_and_detail() {
        String id = newJob();

        assertThat(repository.fail(id, "Processing error", "estourou")).contains(NOW);

        Job job = repository.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getFailure().title()).isEqualTo("Processing error");
        assertThat(job.getFailure().detail()).isEqualTo("estourou");
    }

    @Test
    @DisplayName("job FAILED sempre tem falha, mesmo sem titulo gravado")
    void findById_should_synthesize_failure_when_failed_without_title() {
        String id = newJob();
        // simula dado legado/parcial: terminal sem titulo
        jdbc.sql("update async_jobs set status = 'FAILED', error_title = null where id = cast(:id as uuid)")
                .param("id", id)
                .update();

        Job job = repository.findById(id).orElseThrow();

        assertThat(job.getFailure())
                .as("sem isso o adapter web quebra ao renderizar o Problem Detail")
                .isNotNull();
        assertThat(job.getFailure().title()).isNotBlank();
    }

    @Test
    @DisplayName("progress aplica só enquanto PROCESSING")
    void progress_should_apply_only_while_processing() {
        String id = newJob();

        assertThat(repository.progress(id, 10))
                .as("PENDING nao esta em execucao")
                .isEmpty();

        repository.start(id);
        assertThat(repository.progress(id, 40)).contains(NOW);
        assertThat(repository.findById(id).orElseThrow().getPercentComplete()).isEqualTo(40);
    }

    @Test
    @DisplayName("findStaleActive traz jobs ativos parados antes do corte")
    void findStaleActive_should_return_active_jobs_older_than_the_cut() {
        String old = newJobUpdatedAt(NOW.minus(Duration.ofHours(2)));
        String recent = newJobUpdatedAt(NOW);
        String terminal = newJobUpdatedAt(NOW.minus(Duration.ofHours(2)));
        repository.complete(terminal);

        var stale = repository.findStaleActive(NOW.minus(Duration.ofMinutes(1)), 100);

        assertThat(stale).containsExactly(old);
        assertThat(stale).doesNotContain(recent, terminal);
    }

    @Test
    @DisplayName("findFreshCompleted acha a ultima carga concluida dentro da janela")
    void findFreshCompleted_should_return_last_completed_within_the_window() {
        String id = newJob();
        repository.complete(id);

        assertThat(repository.findFreshCompleted(TYPE, NOW.minus(Duration.ofHours(1))))
                .as("concluido agora, dentro de uma janela de uma hora")
                .isPresent();
        assertThat(repository.findFreshCompleted(TYPE, NOW.plusSeconds(1)))
                .as("fora da janela: o dado ja esfriou")
                .isEmpty();
    }

    @Test
    @DisplayName("findFreshCompleted ignora job ativo — só carga concluída conta como quente")
    void findFreshCompleted_should_ignore_active_jobs() {
        newJob();

        assertThat(repository.findFreshCompleted(TYPE, NOW.minus(Duration.ofHours(1)))).isEmpty();
    }

    @Test
    @DisplayName("id malformado é apenas um job inexistente, não um erro")
    void findById_should_return_empty_when_id_is_not_a_uuid() {
        // o id vem do path da request: qualquer string chega até aqui, e um
        // IllegalArgumentException viraria 500 no lugar do 404 devido
        assertThat(repository.findById("nao-e-uuid")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("transição sobre id malformado é recusada, sem estourar")
    @ValueSource(strings = {"start", "complete", "cancel", "fail", "progress"})
    void transitions_should_be_refused_when_id_is_not_a_uuid(String transition) {
        Optional<Instant> at = switch (transition) {
            case "start" -> repository.start("nao-e-uuid");
            case "complete" -> repository.complete("nao-e-uuid");
            case "cancel" -> repository.cancel("nao-e-uuid");
            case "fail" -> repository.fail("nao-e-uuid", "t", "d");
            default -> repository.progress("nao-e-uuid", 10);
        };

        assertThat(at).isEmpty();
    }

    // --- helpers -------------------------------------------------------------

    private String newJob() {
        String id = UUID.randomUUID().toString();
        repository.create(id, TYPE, null, TYPE);
        return id;
    }

    /** Cria um job e força o last_updated_at, para testar a varredura. */
    private String newJobUpdatedAt(Instant lastUpdatedAt) {
        String id = UUID.randomUUID().toString();
        repository.create(id, TYPE, null, id); // escopo proprio: nao colide com os outros
        jdbc.sql("update async_jobs set last_updated_at = :at where id = cast(:id as uuid)")
                .param("at", lastUpdatedAt.atOffset(ZoneOffset.UTC))
                .param("id", id)
                .update();
        return id;
    }

    private JobStatus status(String id) {
        return repository.findById(id).orElseThrow().getStatus();
    }
}
