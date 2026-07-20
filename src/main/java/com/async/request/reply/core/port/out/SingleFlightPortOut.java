package com.async.request.reply.core.port.out;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Outbound port do single-flight: garante que não exista mais de um job
 * em andamento para a mesma chave de recurso (ex: número da conta).
 * A chave é fornecida pelo consumidor — a lib é agnóstica ao domínio.
 *
 * <p>O guard só é confiável se {@link #peek} + {@link #claim} acontecerem
 * dentro de {@link #withLock} para a mesma chave: o lock serializa a decisão
 * entre instâncias, e o claim deve apontar para um job já persistido.</p>
 */
public interface SingleFlightPortOut {

    /** jobId atualmente associado à chave, se houver. */
    Optional<String> peek(String resourceKey);

    /**
     * Associa a chave ao {@code jobId} (sobrescreve guard anterior).
     * Deve ser chamado apenas dentro de {@link #withLock} e somente após o
     * job estar persistido — assim o guard nunca aponta para job inexistente.
     */
    void claim(String resourceKey, String jobId);

    /**
     * Remove o guard se (e somente se) ele ainda apontar para {@code jobId}
     * (compare-and-delete). Chamado quando o job atinge estado terminal.
     */
    void release(String resourceKey, String jobId);

    /**
     * Executa {@code action} sob lock distribuído da chave, serializando a
     * seção crítica peek → create → claim entre instâncias.
     */
    <T> T withLock(String resourceKey, Supplier<T> action);
}
