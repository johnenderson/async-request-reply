package com.async.request.reply.spi;

/**
 * Base comum das rotinas registradas pelo projeto consumidor.
 * Cada rotina é identificada por um {@link #type()} único.
 */
public interface Routine {

    /** Chave única que identifica a rotina, ex: "pagamento". */
    String type();
}
