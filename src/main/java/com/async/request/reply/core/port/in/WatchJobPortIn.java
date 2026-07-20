package com.async.request.reply.core.port.in;

import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.result.JobWatchView;

import java.util.function.Consumer;

/**
 * Inbound port para acompanhar um job em tempo real: o caller registra um
 * listener e recebe um snapshot do estado atual seguido dos eventos de
 * transição, até fechar a assinatura.
 */
public interface WatchJobPortIn {

    JobWatchView execute(String id, Consumer<JobEvent> listener);
}
