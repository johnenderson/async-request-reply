package com.async.request.reply.core.port.out;

/**
 * Outbound port da política de submissão (decisões do momento do submit),
 * separada da {@link JobPolicyPortOut} (hints HTTP/retenção).
 */
public interface JobSubmissionPolicyPortOut {

    /** Se o coalescing (single-flight) está habilitado. */
    boolean coalesceInFlight();
}
