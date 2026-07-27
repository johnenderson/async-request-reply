package com.async.request.reply.adapter.out.events;

import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.out.JobEventSubscriberPortOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Adapter out: eventos <b>derivados do estado</b>, por polling no repositório
 * (ADR 0004).
 *
 * <p>Sem Redis não há pub/sub, então não existe publicação: o estado no banco é a
 * única fonte de verdade, e este adapter detecta mudança comparando o
 * {@code lastUpdatedAt} entre leituras. Como cada {@link JobEvent} carrega estado
 * (e não delta), perder um intermediário entre dois ciclos é inofensivo — o
 * cliente sempre recebe o estado atual, com o timestamp para reconciliar.</p>
 *
 * <p>A primeira leitura funciona como snapshot: quem abre um stream recebe o
 * estado corrente e, se o job já terminou, o evento terminal na hora — o que
 * mantém a garantia de reconexão sem conclusão perdida.</p>
 */
public class PollingJobEventSubscriberAdapterOut implements JobEventSubscriberPortOut, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PollingJobEventSubscriberAdapterOut.class);

    private final JobRepositoryPortOut repository;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;

    public PollingJobEventSubscriberAdapterOut(JobRepositoryPortOut repository,
                                               AsyncJobsProperties properties) {
        this.repository = repository;
        this.interval = properties.sse().pollInterval();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "async-jobs-events-poll");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public AutoCloseable subscribe(String jobId, Consumer<JobEvent> listener) {
        Watch watch = new Watch(jobId, listener);
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(
                watch::poll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        return () -> task.cancel(false);
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }

    /** Estado de um acompanhamento: emite só quando o instante de atualização muda. */
    private final class Watch {

        private final String jobId;
        private final Consumer<JobEvent> listener;
        private Instant lastSeen;

        private Watch(String jobId, Consumer<JobEvent> listener) {
            this.jobId = jobId;
            this.listener = listener;
        }

        private void poll() {
            try {
                repository.findById(jobId)
                        .map(JobEvent::of)
                        .filter(event -> !event.lastUpdatedAt().equals(lastSeen))
                        .ifPresent(event -> {
                            lastSeen = event.lastUpdatedAt();
                            listener.accept(event);
                        });
            } catch (Exception e) {
                // uma falha de leitura não pode matar o agendamento do stream
                log.debug("Falha ao consultar o estado do job '{}' para eventos: {}", jobId, e.getMessage());
            }
        }
    }
}
