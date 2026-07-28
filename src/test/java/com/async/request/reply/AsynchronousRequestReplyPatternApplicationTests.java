package com.async.request.reply;

import com.async.request.reply.spi.AsyncJobHandler;
import com.async.request.reply.spi.JobContext;
import com.async.request.reply.spi.JobHandler;
import com.async.request.reply.spi.JobReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "async-jobs.coalesce-in-flight=false")
class AsynchronousRequestReplyPatternApplicationTests extends PostgresContainerTestSupport {

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JobReporter reporter;

    MockMvc mvc;

    /** Gate que mantém o handler "lento" ativo até o teste liberar (sem sleep fixo). */
    static final AtomicReference<CountDownLatch> SLOW_GATE = new AtomicReference<>(new CountDownLatch(0));

    /** Coordenação do teste de cancelamento cooperativo, sem sleep fixo. */
    static final AtomicReference<CountDownLatch> COOP_STARTED = new AtomicReference<>(new CountDownLatch(0));
    static final AtomicReference<CountDownLatch> COOP_GATE = new AtomicReference<>(new CountDownLatch(0));
    static final AtomicReference<Boolean> COOP_SAW_CANCELLATION = new AtomicReference<>();

    @BeforeEach
    void setup() {
        truncateJobs();
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        SLOW_GATE.set(new CountDownLatch(1));
        COOP_STARTED.set(new CountDownLatch(1));
        COOP_GATE.set(new CountDownLatch(1));
        COOP_SAW_CANCELLATION.set(null);
    }

    @AfterEach
    void releaseSlowGate() {
        SLOW_GATE.get().countDown();
        COOP_GATE.get().countDown();
    }

    // -------------------------------------------------------------------------
    // Handlers (rotinas do "projeto consumidor") registrados só para os testes
    // -------------------------------------------------------------------------
    @TestConfiguration
    static class TestHandlers {

        @Bean
        JobHandler fastHandler() {
            return new JobHandler() {
                public String type() { return "test"; }
                public void handle(JobContext ctx) { }
            };
        }

        @Bean
        JobHandler idempotentHandler() {
            return new JobHandler() {
                public String type() { return "idempotent-test"; }
                public void handle(JobContext ctx) { }
            };
        }

        @Bean
        JobHandler slowHandler() {
            return new JobHandler() {
                public String type() { return "cancel-test"; }
                public void handle(JobContext ctx) {
                    // bloqueia até o teste liberar — mantém o job ativo de forma determinística
                    TestGate.await(SLOW_GATE.get());
                }
            };
        }

        @Bean
        AsyncJobHandler asyncHandler() {
            return new AsyncJobHandler() {
                public String type() { return "async-test"; }
                public void start(JobContext ctx) {
                    // fire-and-forget: o worker do consumidor concluiria depois
                    // via reporter.complete(ctx.jobId(), ...)
                }
            };
        }

        @Bean
        JobHandler nullHandler() {
            return new JobHandler() {
                public String type() { return "null-test"; }
                public void handle(JobContext ctx) { }
            };
        }

        @Bean
        JobHandler numericNameHandler() {
            return new JobHandler() {
                public String type() { return "123"; }
                public void handle(JobContext ctx) { }
            };
        }

        @Bean
        JobHandler dottedTypeHandler() {
            return new JobHandler() {
                public String type() { return "report.v1_all-items"; }
                public void handle(JobContext ctx) { }
            };
        }

        @Bean
        JobHandler failingHandler() {
            return new JobHandler() {
                public String type() { return "fail-test"; }
                public void handle(JobContext ctx) { throw new IllegalStateException("boom simulado"); }
            };
        }

        /** Rotina que decide parar sozinha ao ver o cancelamento (ADR 0004). */
        @Bean
        JobHandler cooperativeHandler() {
            return new JobHandler() {
                public String type() { return "coop-cancel-test"; }
                public void handle(JobContext ctx) {
                    COOP_STARTED.get().countDown();
                    TestGate.await(COOP_GATE.get());   // espera o teste cancelar
                    COOP_SAW_CANCELLATION.set(ctx.isCancelled());
                }
            };
        }
    }

    // 1. POST /jobs/{type} — 202 + Location + Retry-After
    @Test
    void submitReturns202WithRequiredHeaders() throws Exception {
        mvc.perform(post("/jobs/test"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.statusUrl").isNotEmpty());
    }

    // 1b. type sem handler registrado → 400
    @Test
    void submitUnknownTypeReturns400() throws Exception {
        mvc.perform(post("/jobs/nao-existe"))
                .andExpect(status().isBadRequest());
    }

    // 2. Idempotency-Key — mesmo job retornado em chamadas repetidas
    @Test
    void idempotencyKeyReturnsSameJob() throws Exception {
        MvcResult first = mvc.perform(post("/jobs/idempotent-test").header("Idempotency-Key", "key-abc-123"))
                .andExpect(status().isAccepted()).andReturn();

        MvcResult second = mvc.perform(post("/jobs/idempotent-test").header("Idempotency-Key", "key-abc-123"))
                .andExpect(status().isAccepted()).andReturn();

        String firstId  = first.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
        String secondId = second.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
        assertEquals(firstId, secondId, "Idempotency-Key must return the same jobId");
    }

    // 3. GET status — Retry-After + Expires + campos obrigatórios
    @Test
    void statusEndpointReturnsRequiredHeadersAndFields() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/cancel-test"))
                .andExpect(status().isAccepted()).andReturn();

        String location = post.getResponse().getHeader("Location");

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("Expires"))
                .andExpect(jsonPath("$.status").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.lastUpdatedAt").isNotEmpty());
    }

    // 4. Job concluído → status legivel com estado terminal (sem redirect)
    @Test
    void completedJobReportsCompletedOnStatus() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/test"))
                .andExpect(status().isAccepted()).andReturn();

        String location = post.getResponse().getHeader("Location");
        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        awaitCompleted(jobId);

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(header().exists("Expires"));
    }

    // 6. DELETE /status — cancela → status segue legivel com estado terminal
    @Test
    void cancelJobReturns202AndStatusReportsCancelled() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/cancel-test"))
                .andExpect(status().isAccepted()).andReturn();

        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        mvc.perform(delete("/jobs/{id}/status", jobId)).andExpect(status().isAccepted());

        // o padrao mantem o recurso de status legivel, refletindo o estado cancelado
        mvc.perform(get("/jobs/{id}/status", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.lastUpdatedAt").isNotEmpty())
                .andExpect(header().exists("Expires"));
    }

    // 7. Job inexistente → 404, mesmo quando o id nem tem forma de id
    @Test
    void unknownJobReturns404() throws Exception {
        mvc.perform(get("/jobs/{id}/status", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/jobs/non-existent-id/status"))
                .andExpect(status().isNotFound());
    }

    // 9. (#3/#4) Cancelamento não pode ser sobrescrito por um complete tardio
    @Test
    void lateCompleteDoesNotOverrideCancellation() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/async-test"))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        mvc.perform(delete("/jobs/{id}/status", jobId)).andExpect(status().isAccepted());

        // worker atrasado tenta concluir — deve ser ignorado
        reporter.complete(jobId);

        mvc.perform(get("/jobs/{id}/status", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED"))); // complete tardio nao sobrescreveu
    }

    // 11. type numérico no path é apenas um nome de rotina válido
    @Test
    void numericTypeNameIsAccepted() throws Exception {
        mvc.perform(post("/jobs/123"))
                .andExpect(status().isAccepted());
    }

    @Test
    void typeWithDotHyphenAndUnderscoreIsAccepted() throws Exception {
        mvc.perform(post("/jobs/report.v1_all-items"))
                .andExpect(status().isAccepted());
    }

    @Test
    void invalidTypeCharactersReturn400() throws Exception {
        mvc.perform(post("/jobs/bad%20type"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("type")));
    }

    @Test
    void submitWithoutTypeIsNotRoutable() throws Exception {
        mvc.perform(post("/jobs"))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitWithoutBodyIsAccepted() throws Exception {
        mvc.perform(post("/jobs/test"))
                .andExpect(status().isAccepted());
    }

    // 12. Handler que lança exceção → job FAILED → status 422 com Problem Detail
    @Test
    void failedJobReturns422ProblemDetailOnStatus() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/fail-test"))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        awaitStatusCode(jobId, 422);

        mvc.perform(get("/jobs/{id}/status", jobId))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.title", is("Processing error")))
                .andExpect(jsonPath("$.detail", containsString("boom simulado")));
    }

    // 13. Idempotency-Key reusada com OUTRO type → 422 (nunca devolve job do tipo errado)
    @Test
    void idempotencyKeyReusedWithDifferentTypeIsRejected() throws Exception {
        mvc.perform(post("/jobs/test").header("Idempotency-Key", "cross-type-key"))
                .andExpect(status().isAccepted());

        mvc.perform(post("/jobs/idempotent-test").header("Idempotency-Key", "cross-type-key"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.title", is("Idempotency-Key conflict")))
                .andExpect(jsonPath("$.detail", containsString("idempotent-test")));
    }

    // 14. Cancelamento também pela rota canônica DELETE /jobs/{id}
    @Test
    void cancelViaCanonicalRouteReturns202AndStatusReportsCancelled() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/cancel-test"))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        mvc.perform(delete("/jobs/{id}", jobId)).andExpect(status().isAccepted());
        mvc.perform(get("/jobs/{id}/status", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));
    }

    // 8. Fire-and-forget: handler async + conclusão via JobReporter
    @Test
    void asyncHandlerCompletesViaReporter() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/async-test"))
                .andExpect(status().isAccepted()).andReturn();

        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        // aguarda o start() levar o job a PROCESSING (não completa sozinho)
        awaitStatusBody(jobId, "PROCESSING");

        // worker do consumidor reporta a conclusão; a lib nao serve o dado (ADR
        // 0004): o status apenas passa a dizer que a base esta quente
        assertTrue(reporter.complete(jobId), "o worker precisa saber que o report foi aceito");

        mvc.perform(get("/jobs/{id}/status", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.percentComplete", is(100)));
    }

    // 15. Cancelamento cooperativo: a rotina ve o cancelamento e para sozinha
    @Test
    void cancelledJobIsVisibleToTheRunningRoutine() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/coop-cancel-test"))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        // garante que a rotina esta rodando antes de cancelar
        assertTrue(COOP_STARTED.get().await(10, TimeUnit.SECONDS), "a rotina nao comecou");

        mvc.perform(delete("/jobs/{id}", jobId)).andExpect(status().isAccepted());
        COOP_GATE.get().countDown();   // libera a rotina para consultar o contexto

        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                .until(() -> COOP_SAW_CANCELLATION.get() != null);
        assertTrue(COOP_SAW_CANCELLATION.get(),
                "a rotina precisa ver o cancelamento para poder parar num ponto consistente");

        // a rotina retornou normalmente depois de parar; o complete que a lib
        // tenta em seguida e recusado, e o estado terminal permanece
        mvc.perform(get("/jobs/{id}/status", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));
    }

    // --- helpers de espera (polling com timeout em vez de Thread.sleep) -----

    /** Aguarda o job concluir (status passa a reportar COMPLETED). */
    private void awaitCompleted(String jobId) {
        awaitStatusBody(jobId, "COMPLETED");
    }

    /** Aguarda o status endpoint responder o código informado. */
    private void awaitStatusCode(String jobId, int expected) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> mvc.perform(get("/jobs/{id}/status", jobId))
                        .andReturn().getResponse().getStatus() == expected);
    }

    /** Aguarda o status (corpo) atingir o valor informado. */
    private void awaitStatusBody(String jobId, String expectedStatus) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    var resp = mvc.perform(get("/jobs/{id}/status", jobId)).andReturn().getResponse();
                    return resp.getStatus() == 200
                            && resp.getContentAsString().contains("\"status\":\"" + expectedStatus + "\"");
                });
    }
}
