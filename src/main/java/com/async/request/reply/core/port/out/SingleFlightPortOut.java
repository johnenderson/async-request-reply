package com.async.request.reply.core.port.out;

import java.util.Optional;

/**
 * Outbound port do single-flight: garante que não exista mais de um job
 * em andamento para a mesma chave de recurso (ex: número da conta).
 * A chave é fornecida pelo consumidor — a lib é agnóstica ao domínio.
 */
public interface SingleFlightPortOut {

    /** jobId atualmente associado à chave, se houver. */
    Optional<String> peek(String resourceKey);

    /**
     * Tenta associar (atômico) a chave ao {@code candidateJobId}.
     * Retorna o jobId vencedor: o próprio candidato se conseguiu, ou o já existente.
     */
    String begin(String resourceKey, String candidateJobId);

    /** Sobrescreve a associação (usado quando o guard anterior está obsoleto). */
    void takeOver(String resourceKey, String jobId);
}
