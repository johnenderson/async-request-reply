package com.async.request.reply.core.usecase;

import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.in.WatchJobPortIn;
import com.async.request.reply.core.port.out.JobEventSubscriberPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.result.JobWatchView;

import java.util.function.Consumer;

/**
 * Implementação do {@link WatchJobPortIn}.
 *
 * <p>Confirma que o job existe (para responder 404 antes de abrir stream) e
 * assina os eventos. Não envia snapshot: os eventos são derivados do estado, e a
 * primeira leitura do subscriber já entrega o estado corrente — inclusive o
 * evento terminal, se o job tiver terminado antes da conexão. É isso que mantém
 * a garantia de reconexão sem conclusão perdida.</p>
 */
public class WatchJobUseCase implements WatchJobPortIn {

    private final JobRepositoryPortOut repository;
    private final JobEventSubscriberPortOut subscriber;

    public WatchJobUseCase(JobRepositoryPortOut repository, JobEventSubscriberPortOut subscriber) {
        this.repository = repository;
        this.subscriber = subscriber;
    }

    @Override
    public JobWatchView execute(String id, Consumer<JobEvent> listener) {
        if (repository.findById(id).isEmpty()) {
            return new JobWatchView.NotFound();
        }
        return new JobWatchView.Watching(subscriber.subscribe(id, listener));
    }
}
