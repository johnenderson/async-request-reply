package com.async.request.reply.core.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * Outbound port da janela de frescor: por quanto tempo os dados produzidos por um
 * job daquele {@code type} continuam quentes, e portanto uma nova carga é
 * desnecessária.
 *
 * <p>{@link Optional#empty()} significa "sem janela" — toda submissão dispara
 * carga (comportamento default).</p>
 */
@FunctionalInterface
public interface JobFreshnessPolicyPortOut {

    Optional<Duration> freshnessFor(String type);
}
