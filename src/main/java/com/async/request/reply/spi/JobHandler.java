package com.async.request.reply.spi;

/**
 * SPI (Service Provider Interface) da biblioteca. O projeto consumidor
 * implementa um bean {@code JobHandler} por rotina assíncrona que quer expor.
 *
 * <p>O fluxo: {@code POST /jobs {"type": "...", "payload": {...}}} faz a lib
 * localizar o handler cujo {@link #type()} bate com o {@code type} do request,
 * converter o payload para {@code P} e invocar {@link #handle(Object)} de forma
 * assíncrona. O retorno {@code R} vira o resultado recuperável em
 * {@code GET /jobs/{id}/result}.
 *
 * @param <P> tipo do input da rotina (deserializado do payload JSON)
 * @param <R> tipo do resultado produzido pela rotina
 */
public interface JobHandler<P, R> {

    /** Chave única que identifica a rotina, ex: "relatorio.mensal". */
    String type();

    /** Executa a rotina do projeto consumidor. */
    R handle(P input);
}
