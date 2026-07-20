package com.async.request.reply.adapter.in.web.sse;

import com.async.request.reply.adapter.in.web.dto.JobEventCompleteResponse;
import com.async.request.reply.adapter.in.web.dto.JobEventStatusResponse;
import com.async.request.reply.adapter.in.web.uribuilder.JobUriBuilder;
import com.async.request.reply.autoconfigure.AsyncJobsProperties;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Adapter in (web/SSE): {@code GET /jobs/{id}/events} abre um stream
 * {@code text/event-stream} que entrega um snapshot do estado atual e os
 * eventos de transição até o estado terminal, quando o stream é fechado.
 *
 * <p>Fica FORA do component-scan do adapter web: o bean é registrado pela
 * auto-configuration do SSE somente quando {@code async-jobs.sse.enabled=true}.
 * A {@code resultUrl} é resolvida na thread da request — os listeners rodam em
 * threads do pub/sub, sem request context para o {@link JobUriBuilder}.</p>
 */
@RestController
@RequestMapping("/jobs")
public class JobEventsControllerAdapterIn implements DisposableBean {

    private final WatchJobPortIn watchJob;
    private final JobUriBuilder uris;
    private final long timeoutMillis;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeats;

    public JobEventsControllerAdapterIn(WatchJobPortIn watchJob, JobUriBuilder uris,
                                        AsyncJobsProperties properties) {
        this.watchJob = watchJob;
        this.uris = uris;
        this.timeoutMillis = properties.resultTtl().toMillis();
        this.heartbeatInterval = properties.sse().heartbeat();
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "async-jobs-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        SseSession session = new SseSession(emitter, uris.result(id).toString());

        return switch (watchJob.execute(id, session::deliver)) {
            case JobWatchView.NotFound _ -> ResponseEntity.notFound().build();
            case JobWatchView.Watching(var subscription) -> {
                session.attach(subscription);
                session.scheduleHeartbeat(heartbeats, heartbeatInterval);
                emitter.onCompletion(session::close);
                emitter.onTimeout(session::close);
                emitter.onError(_ -> session.close());
                yield ResponseEntity.ok(emitter);
            }
        };
    }

    @Override
    public void destroy() {
        heartbeats.shutdownNow();
    }

    /**
     * Estado de um stream aberto. Os sends são serializados por
     * {@code synchronized}: snapshot (thread da request), eventos do pub/sub e
     * heartbeats chegam de threads diferentes.
     */
    private static final class SseSession {

        private final SseEmitter emitter;
        private final String resultUrl;
        private AutoCloseable subscription;
        private ScheduledFuture<?> heartbeat;
        private boolean closed;

        private SseSession(SseEmitter emitter, String resultUrl) {
            this.emitter = emitter;
            this.resultUrl = resultUrl;
        }

        synchronized void deliver(JobEvent event) {
            if (closed) {
                return;
            }
            try {
                emitter.send(toSse(event));
            } catch (Exception _) {
                release(); // client desconectou — só libera recursos
                return;
            }
            if (event.terminal()) {
                release();
                emitter.complete(); // fecha o stream após o evento terminal
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
                    this::sendHeartbeat, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }

        private synchronized void sendHeartbeat() {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception _) {
                release();
            }
        }

        synchronized void close() {
            release();
        }

        private void release() {
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
                        .data(new JobEventCompleteResponse(event.jobId(), resultUrl), MediaType.APPLICATION_JSON);
                case FAILED -> statusEvent("failed", event);
                case CANCELLED -> statusEvent("cancelled", event);
            };
        }

        private static SseEmitter.SseEventBuilder statusEvent(String name, JobEvent event) {
            return SseEmitter.event().name(name)
                    .data(new JobEventStatusResponse(event.jobId(), event.status(), event.percentComplete()),
                            MediaType.APPLICATION_JSON);
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
