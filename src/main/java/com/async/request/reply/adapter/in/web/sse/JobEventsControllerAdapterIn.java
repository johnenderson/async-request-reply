package com.async.request.reply.adapter.in.web.sse;

import com.async.request.reply.adapter.in.web.dto.JobEventCompleteResponse;
import com.async.request.reply.adapter.in.web.dto.JobEventStatusResponse;
import com.async.request.reply.adapter.in.web.uribuilder.JobUriBuilder;
import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.event.JobEvent;
import com.async.request.reply.core.port.in.WatchJobPortIn;
import com.async.request.reply.core.result.JobWatchView;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adapter in (web/SSE): {@code GET /jobs/{id}/events} abre um stream
 * {@code text/event-stream} que entrega um snapshot do estado atual e os
 * eventos de transição até o estado terminal, quando o stream é fechado.
 *
 * <p>Os writes ({@link SseEmitter#send}) são bloqueantes; para não travar as
 * threads compartilhadas de pub/sub (Redisson) e do scheduler de heartbeat, eles
 * rodam num <b>pool dedicado</b>, serializados por sessão (ordem preservada, um
 * write por vez). Um cliente lento consome no máximo uma thread do pool — não
 * atrasa a entrega de eventos de outros jobs nem o heartbeat de outros streams.</p>
 *
 * <p>Fica FORA do component-scan do adapter web: o bean é registrado pela
 * auto-configuration do SSE somente quando {@code async-jobs.sse.enabled=true}.
 * A {@code resultUrl} é resolvida na thread da request — os listeners rodam em
 * threads de pub/sub, sem request context para o {@link JobUriBuilder}.</p>
 */
@RestController
@RequestMapping("/jobs")
public class JobEventsControllerAdapterIn implements DisposableBean {

    private final WatchJobPortIn watchJob;
    private final JobUriBuilder uris;
    private final long timeoutMillis;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeatScheduler;
    private final ExecutorService sendPool;

    public JobEventsControllerAdapterIn(WatchJobPortIn watchJob, JobUriBuilder uris,
                                        AsyncJobsProperties properties) {
        this.watchJob = watchJob;
        this.uris = uris;
        this.timeoutMillis = properties.resultTtl().toMillis();
        this.heartbeatInterval = properties.sse().heartbeat();
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("async-jobs-sse-heartbeat"));
        this.sendPool = Executors.newFixedThreadPool(
                properties.sse().sendPoolSize(), daemonFactory("async-jobs-sse-send"));
    }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        SseSession session = new SseSession(emitter, uris.result(id).toString(), new SerialExecutor(sendPool));

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
        sendPool.shutdownNow();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Executor que roda tarefas de uma sessão uma-a-uma sobre um pool
     * compartilhado (preserva ordem e exclusão mútua sem thread dedicada por
     * sessão). Do javadoc de {@link Executor}.
     */
    private static final class SerialExecutor implements Executor {

        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private final Executor delegate;
        private Runnable active;

        SerialExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(() -> {
                try {
                    command.run();
                } finally {
                    scheduleNext();
                }
            });
            if (active == null) {
                scheduleNext();
            }
        }

        private synchronized void scheduleNext() {
            if ((active = tasks.poll()) != null) {
                delegate.execute(active);
            }
        }
    }

    /**
     * Estado de um stream aberto. Os writes rodam serializados (via
     * {@link SerialExecutor}); {@code closed} é volátil para os fast-paths e o
     * teardown é {@code synchronized}.
     */
    private static final class SseSession {

        private final SseEmitter emitter;
        private final String resultUrl;
        private final Executor serial;
        private volatile boolean closed;
        private AutoCloseable subscription;
        private ScheduledFuture<?> heartbeat;

        private SseSession(SseEmitter emitter, String resultUrl, Executor serial) {
            this.emitter = emitter;
            this.resultUrl = resultUrl;
            this.serial = serial;
        }

        /** Enfileira o envio (não bloqueia a thread chamadora — request ou pub/sub). */
        void deliver(JobEvent event) {
            if (closed) {
                return;
            }
            serial.execute(() -> send(event));
        }

        private void send(JobEvent event) {
            if (closed) {
                return;
            }
            try {
                emitter.send(toSse(event));
            } catch (Exception _) {
                close(); // client desconectou
                return;
            }
            if (event.terminal()) {
                close();
                emitter.complete(); // fecha o stream após o evento terminal
            }
        }

        private void enqueueHeartbeat() {
            if (closed) {
                return;
            }
            serial.execute(this::sendHeartbeat);
        }

        private void sendHeartbeat() {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception _) {
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
            } catch (Exception _) {
                // cancelamento de assinatura é best-effort
            }
        }
    }
}
