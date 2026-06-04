package com.async.request.reply.core.port.out;

import com.async.request.reply.core.domain.Job;

/**
 * Outbound port para o processamento assíncrono de um job. O core dispara
 * o processamento através desta interface, sem conhecer a tecnologia usada.
 */
public interface JobProcessorPortOut {

    /** Indica se existe uma rotina (handler) registrada para o {@code type}. */
    boolean supports(String type);

    /** Despacha o job para a rotina correspondente ao seu {@code type}. */
    void process(Job job);
}
