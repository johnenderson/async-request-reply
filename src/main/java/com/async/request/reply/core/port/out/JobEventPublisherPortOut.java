package com.async.request.reply.core.port.out;

import com.async.request.reply.core.event.JobEvent;

/**
 * Outbound port de publicação de eventos de job. Chamado pelos use cases /
 * processor somente quando a transição atômica de estado teve sucesso — um
 * evento publicado sempre reflete um estado realmente persistido.
 *
 * <p>Quando SSE está desligado, a implementação default é no-op.</p>
 */
@FunctionalInterface
public interface JobEventPublisherPortOut {

    void publish(JobEvent event);
}
