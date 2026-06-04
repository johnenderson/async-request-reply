package com.async.request.reply.core.port.out;

import com.async.request.reply.core.domain.Job;

import java.util.Optional;

/**
 * Outbound port para persistência de jobs. O core depende desta interface;
 * o adapter de persistência fornece a implementação concreta.
 */
public interface JobRepositoryPortOut {

    Job save(String type, Object payload, String idempotencyKey);

    Optional<Job> findById(String id);
}
