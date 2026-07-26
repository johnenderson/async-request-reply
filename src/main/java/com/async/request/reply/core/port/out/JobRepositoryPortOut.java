package com.async.request.reply.core.port.out;

import com.async.request.reply.core.domain.Job;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port para persistência de jobs. As transições são <b>atômicas</b>
 * (check-and-set no store) e devolvem o instante efetivamente gravado quando
 * aplicadas, ou {@link Optional#empty()} quando o job já estava em estado
 * terminal — assim concorrência entre instâncias não sobrescreve
 * cancelamentos/conclusões, e quem publica eventos usa o mesmo timestamp que
 * está persistido.
 */
public interface JobRepositoryPortOut {

    /**
     * Cria um novo job com id pré-gerado, ou devolve o existente quando há
     * conflito — dedupe atômico em duas dimensões:
     *
     * <ul>
     *   <li>{@code idempotencyKey} já usada → devolve o job daquela key;</li>
     *   <li>{@code coalescingKey} com job ainda ativo → devolve o job ativo
     *       (single-flight). {@code null} desliga o coalescing.</li>
     * </ul>
     */
    Job create(String id, String type, String idempotencyKey, String coalescingKey);

    Optional<Job> findById(String id);

    /** Job já associado a uma idempotencyKey, se houver. */
    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    /** PENDING → PROCESSING. */
    Optional<Instant> start(String id);

    /** PENDING/PROCESSING → COMPLETED. O resultado é materializado no result store. */
    Optional<Instant> complete(String id);

    /** PENDING/PROCESSING → FAILED, gravando o erro. */
    Optional<Instant> fail(String id, String title, String detail);

    /** PENDING/PROCESSING → CANCELLED. */
    Optional<Instant> cancel(String id);

    /** Atualiza o progresso (0–100) somente enquanto o job está PROCESSING. */
    Optional<Instant> progress(String id, int percent);

    /**
     * Ids de jobs ainda ativos (PENDING/PROCESSING) cuja última atualização é
     * anterior a {@code olderThan} — base para a recuperação de jobs órfãos
     * (instância que caiu antes de processar ou worker que nunca reportou).
     */
    List<String> findStaleActive(Instant olderThan, int limit);

    /**
     * Último job <b>concluído</b> daquele escopo de coalescing cuja conclusão é
     * posterior a {@code completedAfter} — base da janela de frescor: se existe,
     * os dados seguem quentes e uma nova carga é desnecessária.
     *
     * <p>Só {@code COMPLETED} conta: uma carga que falhou não deve suprimir a
     * próxima tentativa.</p>
     */
    Optional<Job> findFreshCompleted(String coalescingKey, Instant completedAfter);

    /**
     * Remove um id do índice de ativos sem transicionar estado. Usado quando o
     * job indexado não existe mais (expirou pelo TTL): sem isso o índice
     * acumularia entradas órfãas para sempre.
     */
    void untrackActive(String id);
}
