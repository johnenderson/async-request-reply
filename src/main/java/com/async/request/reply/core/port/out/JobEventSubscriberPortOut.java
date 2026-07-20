package com.async.request.reply.core.port.out;

import com.async.request.reply.core.event.JobEvent;

import java.util.function.Consumer;

/**
 * Outbound port de assinatura de eventos de um job específico. O listener é
 * invocado para cada evento publicado por qualquer instância da aplicação
 * (a implementação Redis usa pub/sub, então a notificação atravessa instâncias).
 */
public interface JobEventSubscriberPortOut {

    /**
     * Assina os eventos do job. Retorna um handle que cancela a assinatura —
     * o caller DEVE fechá-lo quando o consumo terminar (evento terminal,
     * desconexão ou timeout).
     */
    AutoCloseable subscribe(String jobId, Consumer<JobEvent> listener);
}
