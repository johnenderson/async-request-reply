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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "async-jobs.coalesce-in-flight=false")
class AsynchronousRequestReplyPatternApplicationTests extends ValkeyContainerTestSupport {

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JobReporter reporter;

    MockMvc mvc;

    /** Gate que mantém o handler "lento" ativo até o teste liberar (sem sleep fixo). */
    static final AtomicReference<CountDownLatch> SLOW_GATE = new AtomicReference<>(new CountDownLatch(0));

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        SLOW_GATE.set(new CountDownLatch(1));
    }

    @AfterEach
    void releaseSlowGate() {
        SLOW_GATE.get().countDown();
    }

    // -------------------------------------------------------------------------
    // Handlers (rotinas do "projeto consumidor") registrados só para os testes
    // -------------------------------------------------------------------------
    @TestConfiguration
    static class TestHandlers {

        @Bean
        JobHandler<List<String>> fastHandler() {
            return new JobHandler<>() {
                public String type() { return "test"; }
                public List<String> handle() { return List.of("a", "b", "c"); }
            };
        }

        @Bean
        JobHandler<List<String>> idempotentHandler() {
            return new JobHandler<>() {
                public String type() { return "idempotent-test"; }
                public List<String> handle() { return List.of("x"); }
            };
        }

        @Bean
        JobHandler<List<String>> slowHandler() {
            return new JobHandler<>() {
                public String type() { return "cancel-test"; }
                public List<String> handle() {
                    // bloqueia até o teste liberar — mantém o job ativo de forma determinística
                    try { SLOW_GATE.get().await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return List.of("done");
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
        JobHandler<List<String>> nullHandler() {
            return new JobHandler<>() {
                public String type() { return "null-test"; }
                public List<String> handle() { return null; }
            };
        }

        @Bean
        JobHandler<List<String>> numericNameHandler() {
            return new JobHandler<>() {
                public String type() { return "123"; }
                public List<String> handle() { return List.of("numeric-name"); }
            };
        }

        @Bean
        JobHandler<List<String>> dottedTypeHandler() {
            return new JobHandler<>() {
                public String type() { return "report.v1_all-items"; }
                public List<String> handle() { return List.of("ok"); }
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

    // 4. Job concluído → 303 See Other para /result
    @Test
    void completedJobRedirectsWith303ToResult() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/test"))
                .andExpect(status().isAccepted()).andReturn();

        String location = post.getResponse().getHeader("Location");
        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        awaitCompleted(jobId);

        mvc.perform(get(location))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", containsString("/jobs/" + jobId + "/result")));
    }

    // 5. GET /result — resultado paginado após conclusão
    @Test
    void resultEndpointReturnsPaginatedResult() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/test"))
                .andExpect(status().isAccepted()).andReturn();

        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        awaitCompleted(jobId);

        mvc.perform(get("/jobs/{id}/result", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(1)));
    }

    // 6. DELETE /status — cancela → 410 Gone
    @Test
    void cancelJobReturns202ThenStatusIsGone() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/cancel-test"))
                .andExpect(status().isAccepted()).andReturn();

        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        mvc.perform(delete("/jobs/{id}/status", jobId)).andExpect(status().isAccepted());

        mvc.perform(get("/jobs/{id}/status", jobId)).andExpect(status().isGone());
    }

    // 7. Job inexistente → 404
    @Test
    void unknownJobReturns404() throws Exception {
        mvc.perform(get("/jobs/non-existent-id/status")).andExpect(status().isNotFound());
    }

    // 9. (#3/#4) Cancelamento não pode ser sobrescrito por um complete tardio
    @Test
    void lateCompleteDoesNotOverrideCancellation() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/async-test"))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        mvc.perform(delete("/jobs/{id}/status", jobId)).andExpect(status().isAccepted());

        // worker atrasado tenta concluir — deve ser ignorado
        reporter.complete(jobId, List.of("tarde-demais"));

        mvc.perform(get("/jobs/{id}/status", jobId)).andExpect(status().isGone()); // segue CANCELLED
    }

    // 10. (#5) Resultado nulo → /result 200 com content vazio (sem 500)
    @Test
    void nullResultYieldsEmptyPage() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/null-test"))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        awaitCompleted(jobId);

        mvc.perform(get("/jobs/{id}/result", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements", is(0)));
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

    // 8. Fire-and-forget: handler async + conclusão via JobReporter
    @Test
    void asyncHandlerCompletesViaReporter() throws Exception {
        MvcResult post = mvc.perform(post("/jobs/async-test"))
                .andExpect(status().isAccepted()).andReturn();

        String jobId = post.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        // aguarda o start() levar o job a PROCESSING (não completa sozinho)
        awaitStatusBody(jobId, "PROCESSING");

        // worker do consumidor reporta a conclusão
        reporter.complete(jobId, List.of("pago"));

        // agora o status redireciona para o result
        awaitCompleted(jobId);
        mvc.perform(get("/jobs/{id}/result", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    // --- helpers de espera (polling com timeout em vez de Thread.sleep) -----

    /** Aguarda o job concluir (status redireciona 303 para o /result). */
    private void awaitCompleted(String jobId) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> mvc.perform(get("/jobs/{id}/status", jobId))
                        .andReturn().getResponse().getStatus() == 303);
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
