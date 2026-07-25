package com.async.request.reply.core.usecase;

import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.in.WatchJobPortIn;
import com.async.request.reply.core.port.out.JobEventSubscriberPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import com.async.request.reply.core.result.JobWatchView;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Implementação do {@link WatchJobPortIn}. A ordem é deliberada: assina o
 * tópico ANTES de ler o snapshot — assim uma transição que aconteça no meio
 * nunca se perde. Como cada evento carrega {@code lastUpdatedAt}, um snapshot
 * que chegue atrasado atrás de um evento mais novo é descartável pelo cliente.
 *
 * <p>Não é anotado como componente: o bean só existe quando SSE está
 * habilitado (ver auto-configuration), para o contexto não exigir um
 * {@link JobEventSubscriberPortOut} que talvez não exista.</p>
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
        AutoCloseable subscription = subscriber.subscribe(id, listener);

        Optional<JobEvent> snapshot = repository.findById(id).map(JobEvent::of);
        if (snapshot.isEmpty()) {
            closeQuietly(subscription); // inexistente (ou expirado no meio) → nada a acompanhar
            return new JobWatchView.NotFound();
        }

        listener.accept(snapshot.get());
        return new JobWatchView.Watching(subscription);
    }

    private static void closeQuietly(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception _) {
            // cancelamento de assinatura é best-effort
        }
    }
}
