package com.async.request.reply.adapter.in.web.sse;

import com.async.request.reply.adapter.in.web.dto.JobEventCompleteResponse;
import com.async.request.reply.adapter.in.web.dto.JobEventStatusResponse;
import com.async.request.reply.adapter.in.web.uribuilder.JobUriBuilder;
import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.in.WatchJobPortIn;
import com.async.request.reply.core.result.JobWatchView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Adapter in (web/SSE): {@code GET /jobs/{id}/events} abre um stream
 * {@code text/event-stream} que entrega um snapshot do estado atual e os
 * eventos de transição até o estado terminal, quando o stream é fechado.
 *
 * <p>Concorrência: {@link SseEmitter#send} é bloqueante, então os writes nunca
 * rodam na thread que originou o evento (request ou pub/sub do Redisson). Cada
 * write é despachado para uma <b>thread virtual</b>, serializado por sessão para
 * preservar a ordem. Um cliente que não drena o socket custa uma thread virtual
 * e um backlog próprio limitado ({@code async-jobs.sse.max-pending-events}); ao
 * estourar esse backlog a sessão é encerrada, em vez de acumular memória ou
 * atrasar outros streams.</p>
 *
 * <p>A {@code resultUrl} é resolvida na thread da request — os listeners rodam
 * em threads de pub/sub, sem request context para o {@link JobUriBuilder}.</p>
 */
@RestController
@RequestMapping("/jobs")
public class JobEventsControllerAdapterIn implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JobEventsControllerAdapterIn.class);

    private final WatchJobPortIn watchJob;
    private final JobUriBuilder uris;
    private final long timeoutMillis;
    private final Duration heartbeatInterval;
    private final int maxPendingEvents;
    private final ScheduledExecutorService heartbeatScheduler;
    private final ExecutorService sendExecutor;

    public JobEventsControllerAdapterIn(WatchJobPortIn watchJob, JobUriBuilder uris,
                                        AsyncJobsProperties properties) {
        this.watchJob = watchJob;
        this.uris = uris;
        this.timeoutMillis = properties.resultTtl().toMillis();
        this.heartbeatInterval = properties.sse().heartbeat();
        this.maxPendingEvents = properties.sse().maxPendingEvents();
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "async-jobs-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.sendExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        SseSession session = new SseSession(id, emitter, uris.result(id).toString(),
                new SerialExecutor(sendExecutor, maxPendingEvents));

        return switch (watchJob.execute(id, session::deliver)) {
            case JobWatchView.NotFound _ -> ResponseEntity.notFound().build();
            case JobWatchView.Watching(var subscription) -> {
                session.attach(subscription);
                session.scheduleHeartbeat(heartbeatScheduler, heartbeatInterval);
                emitter.onCompletion(session::close);
                emitter.onTimeout(session::close);
                emitter.onError(_ -> session.close());
                yield ResponseEntity.ok(emitter);
            }
        };
    }

    @Override
    public void destroy() {
        heartbeatScheduler.shutdownNow();
        sendExecutor.shutdownNow();
    }

    /**
     * Fila serial por sessão sobre um executor compartilhado: preserva ordem e
     * exclusão mútua sem thread dedicada, e recusa trabalho quando o backlog
     * passa do teto (cliente lento).
     */
    private static final class SerialExecutor {

        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private final Executor delegate;
        private final int maxPending;
        private boolean running;

        SerialExecutor(Executor delegate, int maxPending) {
            this.delegate = delegate;
            this.maxPending = maxPending;
        }

        /** @return {@code false} se o backlog estourou e a tarefa foi recusada */
        synchronized boolean offer(Runnable command) {
            if (tasks.size() >= maxPending) {
                return false;
            }
            tasks.add(() -> {
                try {
                    command.run();
                } finally {
                    next();
                }
            });
            if (!running) {
                running = true;
                next();
            }
            return true;
        }

        /** Sem writes pendentes nem em curso — usado para não empilhar heartbeats. */
        synchronized boolean idle() {
            return !running && tasks.isEmpty();
        }

        private synchronized void next() {
            Runnable task = tasks.poll();
            if (task == null) {
                running = false;
                return;
            }
            try {
                delegate.execute(task);
            } catch (RejectedExecutionException _) {
                // executor encerrado (shutdown da aplicação): descarta o backlog
                tasks.clear();
                running = false;
            }
        }
    }

    /**
     * Estado de um stream aberto. Os writes rodam serializados; {@code closed}
     * é volátil para os fast-paths e o teardown é {@code synchronized}.
     */
    private static final class SseSession {

        private final String jobId;
        private final SseEmitter emitter;
        private final String resultUrl;
        private final SerialExecutor serial;
        private volatile boolean closed;
        private AutoCloseable subscription;
        private ScheduledFuture<?> heartbeat;

        private SseSession(String jobId, SseEmitter emitter, String resultUrl, SerialExecutor serial) {
            this.jobId = jobId;
            this.emitter = emitter;
            this.resultUrl = resultUrl;
            this.serial = serial;
        }

        /** Enfileira o envio; não bloqueia a thread chamadora (request ou pub/sub). */
        void deliver(JobEvent event) {
            if (closed) {
                return;
            }
            if (!serial.offer(() -> send(event))) {
                log.warn("Stream SSE do job '{}' com backlog cheio; cliente nao esta drenando — encerrando",
                        jobId);
                close();
                emitter.complete();
            }
        }

        private void send(JobEvent event) {
            if (closed) {
                return;
            }
            try {
                emitter.send(toSse(event));
            } catch (Exception e) {
                log.debug("Stream SSE do job '{}' encerrado durante o envio: {}", jobId, e.getMessage());
                close(); // client desconectou
                return;
            }
            if (event.terminal()) {
                close();
                emitter.complete(); // fecha o stream após o evento terminal
            }
        }

        private void enqueueHeartbeat() {
            if (closed || !serial.idle()) {
                return; // já há write pendente: keepalive seria redundante
            }
            serial.offer(this::sendHeartbeat);
        }

        private void sendHeartbeat() {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                log.debug("Keepalive do job '{}' falhou; encerrando stream: {}", jobId, e.getMessage());
                close();
            }
        }

        synchronized void attach(AutoCloseable subscription) {
            if (closed) {
                closeQuietly(subscription); // terminal entregue antes do attach
                return;
            }
            this.subscription = subscription;
        }

        synchronized void scheduleHeartbeat(ScheduledExecutorService scheduler, Duration interval) {
            if (closed) {
                return;
            }
            heartbeat = scheduler.scheduleAtFixedRate(
                    this::enqueueHeartbeat, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }

        synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            if (subscription != null) {
                closeQuietly(subscription);
            }
        }

        private SseEmitter.SseEventBuilder toSse(JobEvent event) {
            return switch (event.status()) {
                case PENDING -> statusEvent("status", event);
                case PROCESSING -> statusEvent(event.percentComplete() == null ? "status" : "progress", event);
                case COMPLETED -> SseEmitter.event().name("complete")
                        .data(new JobEventCompleteResponse(event.jobId(), resultUrl, event.lastUpdatedAt()),
                                MediaType.APPLICATION_JSON);
                case FAILED -> statusEvent("failed", event);
                case CANCELLED -> statusEvent("cancelled", event);
            };
        }

        private static SseEmitter.SseEventBuilder statusEvent(String name, JobEvent event) {
            return SseEmitter.event().name(name)
                    .data(new JobEventStatusResponse(event.jobId(), event.status(),
                            event.percentComplete(), event.lastUpdatedAt()), MediaType.APPLICATION_JSON);
        }

        private static void closeQuietly(AutoCloseable subscription) {
            try {
                subscription.close();
            } catch (Exception e) {
                log.debug("Falha ao cancelar assinatura de eventos: {}", e.getMessage());
            }
        }
    }
}
