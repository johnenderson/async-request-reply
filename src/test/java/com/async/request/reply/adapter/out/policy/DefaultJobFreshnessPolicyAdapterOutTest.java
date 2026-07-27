package com.async.request.reply.adapter.out.policy;

import com.async.request.reply.config.AsyncJobsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultJobFreshnessPolicyAdapterOut")
class DefaultJobFreshnessPolicyAdapterOutTest {

    private static final Duration UMA_HORA = Duration.ofHours(1);
    private static final Duration MEIA_HORA = Duration.ofMinutes(30);

    @Test
    @DisplayName("sem janela quando o frescor está desligado, mesmo com janelas configuradas")
    void freshnessFor_should_be_empty_when_disabled() {
        var policy = policy(new AsyncJobsProperties.Freshness(false, UMA_HORA, Map.of("relatorio", MEIA_HORA)));

        assertThat(policy.freshnessFor("relatorio")).isEmpty();
    }

    @Test
    @DisplayName("usa a janela default quando o type não tem override")
    void freshnessFor_should_return_default_window_when_type_has_no_override() {
        var policy = policy(new AsyncJobsProperties.Freshness(true, UMA_HORA, Map.of()));

        assertThat(policy.freshnessFor("relatorio")).contains(UMA_HORA);
    }

    @Test
    @DisplayName("janela por type tem precedência sobre a default")
    void freshnessFor_should_prefer_per_type_window_over_default() {
        var policy = policy(new AsyncJobsProperties.Freshness(true, UMA_HORA, Map.of("relatorio", MEIA_HORA)));

        assertThat(policy.freshnessFor("relatorio")).contains(MEIA_HORA);
        assertThat(policy.freshnessFor("outro-type")).contains(UMA_HORA);
    }

    @Test
    @DisplayName("sem janela quando ligado mas nada configurado — não suprime carga por acidente")
    void freshnessFor_should_be_empty_when_enabled_without_any_window() {
        var policy = policy(new AsyncJobsProperties.Freshness(true, null, Map.of()));

        assertThat(policy.freshnessFor("relatorio")).isEmpty();
    }

    private static DefaultJobFreshnessPolicyAdapterOut policy(AsyncJobsProperties.Freshness freshness) {
        return new DefaultJobFreshnessPolicyAdapterOut(freshness);
    }
}
