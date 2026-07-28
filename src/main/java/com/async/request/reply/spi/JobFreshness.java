package com.async.request.reply.spi;

import java.time.Instant;
import java.util.Optional;

/**
 * Consulta de frescura, injetável pelo projeto consumidor no endpoint de domínio
 * dele.
 *
 * <p>Existe porque a lib não serve dados (ADR 0004): o cliente lê o resultado na
 * base do consumidor, e nada o impede de ler antes de a carga terminar. O
 * "só leia quando estiver quente" deixa de ser garantia e passa a ser
 * informação — então a resposta de domínio precisa poder dizer <b>de quando</b>
 * são os dados que está devolvendo.</p>
 *
 * <pre>{@code
 * @GetMapping("/contas/aptas")
 * ResponseEntity<ContasResponse> aptas() {
 *     var contas = repository.buscarAptas();       // SQL de dominio, otimizado
 *     return ResponseEntity.ok(new ContasResponse(
 *             contas,
 *             freshness.lastRefreshedAt("contas").orElse(null),  // "dados de"
 *             freshness.isFresh("contas")));                     // dentro da janela?
 * }
 * }</pre>
 */
public interface JobFreshness {

    /**
     * Quando a última carga daquele {@code type} concluiu — a idade real dos
     * dados. Vazio significa que nenhuma carga concluiu (nunca rodou, ainda está
     * rodando, ou a última falhou); uma carga que falhou não deixa dado quente.
     */
    Optional<Instant> lastRefreshedAt(String type);

    /**
     * Se a última carga concluiu dentro da janela configurada para o
     * {@code type} — a mesma janela que faz a lib poupar uma carga nova.
     *
     * <p>Sempre {@code false} quando não há janela configurada: sem janela, nada
     * é considerado quente, e é por isso que toda submissão dispara carga.</p>
     */
    boolean isFresh(String type);
}
