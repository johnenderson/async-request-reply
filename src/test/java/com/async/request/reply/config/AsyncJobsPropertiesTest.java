package com.async.request.reply.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Configuração inválida falha o startup em vez de ser silenciosamente
 * corrigida: um valor absurdo descoberto em produção custa mais que um
 * container que não sobe.
 */
@DisplayName("AsyncJobsProperties")
class AsyncJobsPropertiesTest {

    @Test
    @DisplayName("valores ausentes assumem defaults utilizáveis")
    void should_apply_defaults_when_nothing_is_configured() {
        AsyncJobsProperties properties = properties(null, null);

        assertThat(properties.retention()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.retryAfterSeconds()).isEqualTo(5);
        assertThat(properties.freshness().enabled()).isFalse();
        assertThat(properties.freshness().perType()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("recusa duracao nao positiva")
    @CsvSource({"PT0S", "PT-5M"})
    void should_reject_non_positive_retention(Duration invalid) {
        assertThatThrownBy(() -> properties(invalid, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("async-jobs.retention");
    }

    /**
     * Janela de frescor maior que a retenção é contraditória: o job concluído
     * seria expurgado antes de a janela fechar, e a lib refaria a carga achando
     * que o dado esfriou — o oposto do que a configuração pediu.
     */
    @Test
    @DisplayName("recusa janela de frescor maior que a retencao")
    void should_reject_freshness_window_longer_than_retention() {
        AsyncJobsProperties.Freshness freshness =
                new AsyncJobsProperties.Freshness(true, Duration.ofHours(6), Map.of());

        assertThatThrownBy(() -> properties(Duration.ofHours(1), freshness))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("async-jobs.retention")
                .hasMessageContaining("frescor");
    }

    @Test
    @DisplayName("recusa janela por type maior que a retencao")
    void should_reject_per_type_window_longer_than_retention() {
        AsyncJobsProperties.Freshness freshness = new AsyncJobsProperties.Freshness(
                true, Duration.ofMinutes(10), Map.of("contas", Duration.ofDays(1)));

        assertThatThrownBy(() -> properties(Duration.ofHours(1), freshness))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contas");
    }

    @Test
    @DisplayName("aceita janela de frescor dentro da retencao")
    void should_accept_freshness_window_within_retention() {
        AsyncJobsProperties.Freshness freshness = new AsyncJobsProperties.Freshness(
                true, Duration.ofMinutes(30), Map.of("contas", Duration.ofHours(1)));

        assertThat(properties(Duration.ofHours(1), freshness).freshness().defaultWindow())
                .isEqualTo(Duration.ofMinutes(30));
    }

    /**
     * O frescor procura a última carga concluída pela `coalescing_key`, que só é
     * gravada quando o coalescing está ligado. Sem essa validação, ligar apenas
     * `freshness.enabled` seria um no-op silencioso: a configuração pede para
     * poupar carga e nada acontece.
     */
    @Test
    @DisplayName("recusa frescor ligado sem coalescing: seria um no-op silencioso")
    void should_reject_freshness_without_coalescing() {
        AsyncJobsProperties.Freshness freshness =
                new AsyncJobsProperties.Freshness(true, Duration.ofMinutes(30), Map.of());

        assertThatThrownBy(() -> new AsyncJobsProperties(
                null, null, false, null, null, freshness, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("async-jobs.coalesce-in-flight");
    }

    @Test
    @DisplayName("aceita frescor ligado junto com coalescing")
    void should_accept_freshness_with_coalescing() {
        AsyncJobsProperties.Freshness freshness =
                new AsyncJobsProperties.Freshness(true, Duration.ofMinutes(30), Map.of());

        AsyncJobsProperties properties = new AsyncJobsProperties(
                null, null, true, null, null, freshness, null);

        assertThat(properties.freshness().enabled()).isTrue();
    }

    /** Frescor desligado não impõe relação com a retenção: a janela é inerte. */
    @Test
    @DisplayName("ignora a relacao quando o frescor esta desligado")
    void should_ignore_the_relation_when_freshness_is_disabled() {
        AsyncJobsProperties.Freshness freshness =
                new AsyncJobsProperties.Freshness(false, Duration.ofDays(7), Map.of());

        assertThat(properties(Duration.ofHours(1), freshness).freshness().enabled()).isFalse();
    }

    /** Coalescing ligado porque o frescor depende dele (ver teste acima). */
    private static AsyncJobsProperties properties(Duration retention,
                                                  AsyncJobsProperties.Freshness freshness) {
        return new AsyncJobsProperties(retention, null, true, null, null, freshness, null);
    }
}
