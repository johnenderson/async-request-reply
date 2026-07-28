package com.async.request.reply.core.usecase;

import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import com.async.request.reply.core.port.out.JobFreshnessPolicyPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.spi.JobFreshness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JobFreshnessUseCase")
class JobFreshnessUseCaseTest {

    private static final String TYPE = "contas";
    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");

    private final JobRepositoryPortOut repository = mock(JobRepositoryPortOut.class);
    private final CoalescingKeyPortOut coalescingKey = type -> type;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private JobFreshness freshness(Duration window) {
        JobFreshnessPolicyPortOut policy = _ -> Optional.ofNullable(window);
        return new JobFreshnessUseCase(repository, policy, coalescingKey, clock);
    }

    @Test
    @DisplayName("devolve o instante da última carga concluída")
    void lastRefreshedAt_should_return_the_last_completed_instant() {
        Instant completedAt = NOW.minus(Duration.ofMinutes(10));
        when(repository.findLastCompletedAt(TYPE)).thenReturn(Optional.of(completedAt));

        assertThat(freshness(Duration.ofHours(1)).lastRefreshedAt(TYPE)).contains(completedAt);
    }

    @Test
    @DisplayName("vazio quando nenhuma carga concluiu")
    void lastRefreshedAt_should_be_empty_when_no_load_has_completed() {
        when(repository.findLastCompletedAt(TYPE)).thenReturn(Optional.empty());

        assertThat(freshness(Duration.ofHours(1)).lastRefreshedAt(TYPE)).isEmpty();
    }

    @Test
    @DisplayName("quente quando a última carga está dentro da janela")
    void isFresh_should_be_true_within_the_window() {
        when(repository.findLastCompletedAt(TYPE))
                .thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(30))));

        assertThat(freshness(Duration.ofHours(1)).isFresh(TYPE)).isTrue();
    }

    @Test
    @DisplayName("frio quando a última carga é mais velha que a janela")
    void isFresh_should_be_false_outside_the_window() {
        when(repository.findLastCompletedAt(TYPE))
                .thenReturn(Optional.of(NOW.minus(Duration.ofHours(2))));

        assertThat(freshness(Duration.ofHours(1)).isFresh(TYPE)).isFalse();
    }

    /**
     * Sem janela nada é quente — a mesma razão pela qual toda submissão dispara
     * carga. Devolver {@code true} aqui faria a resposta de domínio prometer
     * frescura que a lib não está garantindo.
     */
    @Test
    @DisplayName("frio quando não há janela configurada, mesmo com carga recente")
    void isFresh_should_be_false_when_no_window_is_configured() {
        when(repository.findLastCompletedAt(TYPE)).thenReturn(Optional.of(NOW));

        assertThat(freshness(null).isFresh(TYPE)).isFalse();
    }

    @Test
    @DisplayName("frio quando nenhuma carga concluiu")
    void isFresh_should_be_false_when_no_load_has_completed() {
        when(repository.findLastCompletedAt(TYPE)).thenReturn(Optional.empty());

        assertThat(freshness(Duration.ofHours(1)).isFresh(TYPE)).isFalse();
    }
}
