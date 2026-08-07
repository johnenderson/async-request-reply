package com.async.request.reply;

import com.async.request.reply.spi.JobContext;
import com.async.request.reply.spi.JobHandler;
import com.jayway.jsonpath.JsonPath;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * E2E sobre Tomcat REAL (porta aleatória) + Postgres real: exercita o fluxo
 * principal do padrão pela borda HTTP de verdade — URLs absolutas construídas a
 * partir da request, formato dos headers, stream SSE sobre socket.
 * Complementa os testes MockMvc, que não passam pelo servlet container.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "async-jobs.coalesce-in-flight=false",
                "async-jobs.retry-after-seconds=1"
        })
class TomcatEndToEndIntegrationTest extends PostgresContainerTestSupport {

    @LocalServerPort
    int port;

    /** Mantém o handler "lento" ativo até o teste liberar (sem sleep fixo). */
    static final AtomicReference<CountDownLatch> GATE = new AtomicReference<>(new CountDownLatch(0));

    /** Redirect.NEVER: nenhuma resposta do contrato redireciona — se aparecer, o teste falha. */
    final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @BeforeEach
    void resetGate() {
        truncateJobs();
        GATE.set(new CountDownLatch(1));
    }

    @AfterEach
    void releaseGate() {
        GATE.get().countDown();
    }

    @TestConfiguration
    static class Handlers {

        @Bean
        JobHandler e2eReportHandler() {
            return new JobHandler() {
                public String type() { return "e2e-report"; }
                // vazio de proposito: este teste cobre o ciclo de vida pela
                // borda HTTP real, nao o efeito da rotina
                public void handle(JobContext ctx) { }
            };
        }

        @Bean
        JobHandler e2eSlowHandler() {
            return new JobHandler() {
                public String type() { return "e2e-slow"; }
                public void handle(JobContext ctx) {
                    TestGate.await(GATE.get());
                }
            };
        }
    }

    // -------------------------------------------------------------------------
    // Fluxo principal: submit → 202 → acompanhamento → estado terminal legível
    // -------------------------------------------------------------------------

    @Test
    void fullLifecycleOverRealHttp() throws Exception {
        // 1. Submit: 202 + Location absoluto + Retry-After + body com as duas URLs
        HttpResponse<String> submitted = post("/jobs/e2e-report");
        assertThat(submitted.statusCode()).isEqualTo(202);

        String statusUrl = header(submitted, "Location");
        String jobId = JsonPath.read(submitted.body(), "$.jobId");
        assertThat(statusUrl).isEqualTo("http://localhost:" + port + "/jobs/" + jobId + "/status");
        assertThat(header(submitted, "Retry-After")).isEqualTo("1");
        assertThat(JsonPath.<String>read(submitted.body(), "$.statusUrl")).isEqualTo(statusUrl);
        assertThat(JsonPath.<String>read(submitted.body(), "$.eventsUrl"))
                .isEqualTo("http://localhost:" + port + "/jobs/" + jobId + "/events");

        // 2. Ao concluir, o recurso de status segue legivel e diz COMPLETED — a
        //    lib nao serve dados (ADR 0004), logo nao ha redirect para resultado
        HttpResponse<String> completed = awaitStatus(statusUrl, "COMPLETED");
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(completed.headers().firstValue("Location")).isEmpty();
        assertHttpDate(header(completed, "Expires"));
        assertThat(JsonPath.<Integer>read(completed.body(), "$.percentComplete")).isEqualTo(100);
    }

    @Test
    void inProgressStatusExposesPollingHints() throws Exception {
        HttpResponse<String> submitted = post("/jobs/e2e-slow");
        String statusUrl = header(submitted, "Location");

        HttpResponse<String> status = get(statusUrl);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(status.body(), "$.status")).isIn("PENDING", "PROCESSING");
        assertThat(header(status, "Retry-After")).isEqualTo("1");
        assertHttpDate(header(status, "Expires"));
    }

    @Test
    void cancelOverRealHttpMakesStatusReportCancelled() throws Exception {
        HttpResponse<String> submitted = post("/jobs/e2e-slow");
        String jobId = JsonPath.read(submitted.body(), "$.jobId");

        HttpResponse<String> cancel = delete("/jobs/" + jobId + "/status");
        assertThat(cancel.statusCode()).isEqualTo(202);

        HttpResponse<String> status = get(url("/jobs/" + jobId + "/status"));
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(status.body(), "$.status")).isEqualTo("CANCELLED");
    }

    @Test
    void idempotencyKeyReturnsSameJobOverRealHttp() throws Exception {
        HttpResponse<String> first = post("/jobs/e2e-slow", "Idempotency-Key", "e2e-key-1");
        HttpResponse<String> second = post("/jobs/e2e-slow", "Idempotency-Key", "e2e-key-1");

        String firstId = JsonPath.read(first.body(), "$.jobId");
        String secondId = JsonPath.read(second.body(), "$.jobId");
        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void unknownTypeReturns400ProblemDetail() throws Exception {
        HttpResponse<String> response = post("/jobs/tipo-inexistente");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(header(response, "Content-Type")).contains("application/problem+json");
        assertThat(JsonPath.<String>read(response.body(), "$.detail")).contains("tipo-inexistente");
    }

    @Test
    void unknownJobReturns404() throws Exception {
        assertThat(get(url("/jobs/" + UUID.randomUUID() + "/status")).statusCode()).isEqualTo(404);
        // id que nem tem forma de id ainda e "job inexistente", nao erro de servidor
        assertThat(get(url("/jobs/nao-existe/status")).statusCode()).isEqualTo(404);
    }

    // -------------------------------------------------------------------------
    // SSE: snapshot → complete → stream fechado pelo servidor
    // -------------------------------------------------------------------------

    @Test
    void sseStreamDeliversSnapshotThenCompleteOverRealHttp() throws Exception {
        HttpResponse<String> submitted = post("/jobs/e2e-slow");
        String jobId = JsonPath.read(submitted.body(), "$.jobId");

        HttpRequest open = HttpRequest.newBuilder(URI.create(url("/jobs/" + jobId + "/events")))
                .header("Accept", "text/event-stream")
                .GET().build();
        HttpResponse<InputStream> stream = http.send(open, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {

            // snapshot chega antes de qualquer transição terminal, com timestamp
            // (permite ao cliente reordenar/descartar em corrida snapshot vs evento)
            readUntil(reader, line -> line.startsWith("event:status"));
            String snapshot = readUntil(reader, line -> line.startsWith("data:")).getLast();
            assertThat(snapshot).contains(jobId);
            assertThat(JsonPath.<String>read(snapshot.substring("data:".length()), "$.lastUpdatedAt"))
                    .isNotBlank();

            GATE.get().countDown(); // libera o handler → COMPLETED → push

            // o evento complete diz apenas "terminou, neste instante": o cliente
            // le o resultado no endpoint de dominio dele (ADR 0004)
            readUntil(reader, line -> line.startsWith("event:complete"));
            String completeData = readUntil(reader, line -> line.startsWith("data:"))
                    .getLast().substring("data:".length());
            assertThat(JsonPath.<String>read(completeData, "$.jobId")).isEqualTo(jobId);
            assertThat(JsonPath.<String>read(completeData, "$.lastUpdatedAt")).isNotBlank();

            awaitStreamEnd(reader); // evento terminal fecha o stream no servidor
        }

        // e o status confirma o mesmo estado terminal, para quem chegou depois
        assertThat(JsonPath.<String>read(get(url("/jobs/" + jobId + "/status")).body(), "$.status"))
                .isEqualTo("COMPLETED");
    }

    @Test
    void sseForUnknownJobReturns404() throws Exception {
        HttpRequest open = HttpRequest.newBuilder(URI.create(url("/jobs/" + UUID.randomUUID() + "/events")))
                .header("Accept", "text/event-stream")
                .GET().build();
        assertThat(http.send(open, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    }

    // --- helpers -------------------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> post(String path, String... headerPairs) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)))
                .POST(HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headerPairs.length; i += 2) {
            request.header(headerPairs[i], headerPairs[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String absoluteUrl) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(absoluteUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name)
                .orElseThrow(() -> new AssertionError("header ausente: " + name));
    }

    /** Polling com timeout até o status endpoint reportar o estado esperado. */
    private HttpResponse<String> awaitStatus(String statusUrl, String expectedStatus) {
        AtomicReference<HttpResponse<String>> last = new AtomicReference<>();
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    HttpResponse<String> response = get(statusUrl);
                    last.set(response);
                    return response.statusCode() == 200
                            && expectedStatus.equals(JsonPath.read(response.body(), "$.status"));
                });
        return last.get();
    }

    /** Lê linhas do stream SSE até o predicado casar — com timeout para a suíte nunca travar. */
    private static List<String> readUntil(BufferedReader reader, Predicate<String> stop) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            List<String> lines = new ArrayList<>();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                    if (stop.test(line)) {
                        return lines;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new AssertionError("stream terminou sem a linha esperada; recebido: " + lines);
        }).get(10, TimeUnit.SECONDS);
    }

    /** Aguarda o servidor encerrar o stream (fim após evento terminal). */
    private static void awaitStreamEnd(BufferedReader reader) throws Exception {
        CompletableFuture.runAsync(() -> {
            try {
                while (reader.readLine() != null) {
                    // drena eventuais keepalives até o fim do stream
                }
            } catch (IOException _) {
                // conexão encerrada pelo servidor também conta como fim
            }
        }).get(10, TimeUnit.SECONDS);
    }

    /** Header de data HTTP deve ser IMF-fixdate (RFC 9110 / RFC 1123). */
    private static void assertHttpDate(String value) {
        assertThat(value).endsWith("GMT");
        assertThatCode(() -> DateTimeFormatter.RFC_1123_DATE_TIME.parse(value))
                .as("Expires deve ser um HTTP-date RFC 1123: %s", value)
                .doesNotThrowAnyException();
    }
}
