package com.async.request.reply.core.port.out;

import com.async.request.reply.core.domain.Job;

import java.time.Instant;

/**
 * Outbound port para a policy de jobs: por quanto tempo os resultados são
 * retidos e com que frequência os clients devem fazer polling. O core depende
 * desta interface; um adapter fornece a implementação concreta (podendo, no
 * futuro, vir de config, feature flag, banco, etc.).
 */
public interface JobPolicyPortOut {

    int retryAfterSeconds();

    boolean coalesceInFlight();

    Instant expiresAt(Job job);
}
