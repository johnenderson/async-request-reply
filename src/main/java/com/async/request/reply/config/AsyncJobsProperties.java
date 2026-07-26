package com.async.request.reply.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Propriedades da biblioteca. Valores ausentes assumem defaults; valores
 * explicitamente inválidos falham o startup (fail-fast) em vez de serem
 * silenciosamente corrigidos.
 *
 * <p>Mora em um pacote neutro ({@code config}) para os adapters não
 * dependerem do pacote {@code autoconfigure}.</p>
 */
@ConfigurationProperties(prefix = "async-jobs")
public record AsyncJobsProperties(
        Duration resultTtl,
        Integer retryAfterSeconds,
        boolean coalesceInFlight,
        String keyPrefix,
        Processing processing,
        Sse sse,
        Recovery recovery
) {

    public AsyncJobsProperties {
        resultTtl = (resultTtl == null) ? Duration.ofHours(1) : resultTtl;
        requirePositive(resultTtl, "async-jobs.result-ttl", "PT1H");

        retryAfterSeconds = (retryAfterSeconds == null) ? 5 : retryAfterSeconds;
        if (retryAfterSeconds <= 0) {
            throw new IllegalArgumentException(
                    "async-jobs.retry-after-seconds deve ser positivo, mas foi " + retryAfterSeconds);
        }

        // vazio = sem prefixo; usar quando mais de uma aplicação compartilha o banco
        keyPrefix = (keyPrefix == null) ? "" : keyPrefix.trim();

        processing = (processing == null) ? new Processing(null) : processing;
        sse = (sse == null) ? new Sse(null, null) : sse;
        recovery = (recovery == null) ? new Recovery(null, null, null, null) : recovery;
    }

    /**
     * Execução dos jobs. A lib usa um executor <b>próprio</b> de threads
     * virtuais — não o executor default da aplicação — para que rotinas de longa
     * duração não concorram com o {@code @Async} do projeto consumidor.
     */
    public record Processing(Integer concurrencyLimit) {

        public Processing {
            // threads virtuais são baratas; o limite existe como backpressure
            // (ao saturar, o submit espera por vaga em vez de acumular sem fim)
            concurrencyLimit = (concurrencyLimit == null) ? 256 : concurrencyLimit;
            if (concurrencyLimit <= 0) {
                throw new IllegalArgumentException(
                        "async-jobs.processing.concurrency-limit deve ser positivo, mas foi " + concurrencyLimit);
            }
        }
    }

    /** Stream de eventos SSE ({@code GET /jobs/{id}/events}). */
    public record Sse(Duration heartbeat, Integer maxPendingEvents) {

        public Sse {
            heartbeat = (heartbeat == null) ? Duration.ofSeconds(15) : heartbeat;
            requirePositive(heartbeat, "async-jobs.sse.heartbeat", "PT15S");

            // backlog por sessão: um cliente que não drena o socket é derrubado
            // ao exceder este limite, em vez de acumular eventos em memória
            maxPendingEvents = (maxPendingEvents == null) ? 64 : maxPendingEvents;
            if (maxPendingEvents <= 0) {
                throw new IllegalArgumentException(
                        "async-jobs.sse.max-pending-events deve ser positivo, mas foi " + maxPendingEvents);
            }
        }
    }

    /**
     * Janela de frescor: enquanto um job do mesmo escopo concluiu há menos que a
     * janela, os dados seguem quentes e uma nova carga é desnecessária. Distinta
     * do single-flight, que cobre job ainda em andamento.
     */
    public record Freshness(boolean enabled, Duration defaultWindow, Map<String, Duration> perType) {
    }

    /**
     * Recuperação de jobs órfãos: sem ela, um job cuja instância caiu antes de
     * processar ficaria PENDING até o TTL, e o cliente faria polling eterno.
     *
     * <p>O liga/desliga é {@code async-jobs.recovery.enabled}, avaliado como
     * condição de criação dos beans (por isso não aparece como campo aqui; está
     * declarado em {@code additional-spring-configuration-metadata.json}).</p>
     */
    public record Recovery(Duration scanInterval, Duration redispatchAfter,
                           Duration processingTimeout, Integer batchSize) {

        public Recovery {
            scanInterval = (scanInterval == null) ? Duration.ofSeconds(30) : scanInterval;
            requirePositive(scanInterval, "async-jobs.recovery.scan-interval", "PT30S");

            redispatchAfter = (redispatchAfter == null) ? Duration.ofMinutes(1) : redispatchAfter;
            requirePositive(redispatchAfter, "async-jobs.recovery.redispatch-after", "PT1M");

            processingTimeout = (processingTimeout == null) ? Duration.ofMinutes(15) : processingTimeout;
            requirePositive(processingTimeout, "async-jobs.recovery.processing-timeout", "PT15M");

            batchSize = (batchSize == null) ? 100 : batchSize;
            if (batchSize <= 0) {
                throw new IllegalArgumentException(
                        "async-jobs.recovery.batch-size deve ser positivo, mas foi " + batchSize);
            }
        }
    }

    private static void requirePositive(Duration value, String property, String example) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    property + " deve ser positivo (ex.: " + example + "), mas foi " + value);
        }
    }
}
