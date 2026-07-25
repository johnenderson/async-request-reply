package com.async.request.reply;

import com.async.request.reply.spi.JobHandler;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Single-flight com chave por {@code type}: requests do mesmo type em andamento
 * compartilham o mesmo jobId (coalescing automático, sem o consumidor enviar chave).
 */
@SpringBootTest(properties = "async-jobs.coalesce-in-flight=true")
class SingleFlightTest extends ValkeyContainerTestSupport {

    @Autowired
    WebApplicationContext wac;

    MockMvc mvc;

    /** Mantém o job ativo enquanto os dois submits acontecem (sem sleep fixo). */
    static final AtomicReference<CountDownLatch> GATE = new AtomicReference<>(new CountDownLatch(0));

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        GATE.set(new CountDownLatch(1));
    }

    @AfterEach
    void releaseGate() {
        GATE.get().countDown();
    }

    @TestConfiguration
    static class Handlers {
        @Bean
        JobHandler<List<String>> slowSfHandler() {
            return new JobHandler<>() {
                public String type() { return "sf-test"; }
                public List<String> handle() {
                    TestGate.await(GATE.get());
                    return List.of("ativos", "inativos");
                }
            };
        }
    }

    @Test
    void sameTypeWhileActiveReturnsSameJobId() throws Exception {
        String id1 = submit();
        String id2 = submit();

        assertEquals(id1, id2, "mesmo type em andamento deve retornar o mesmo jobId");
    }

    /**
     * Regressão da corrida claim × persistência: dois submits SIMULTÂNEOS do
     * mesmo type não podem criar dois jobs (peek → create → claim roda sob o
     * lock distribuído da chave).
     */
    @Test
    void concurrentSubmitsOfSameTypeShareSameJobId() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> { start.await(); return submit(); });
            Future<String> second = pool.submit(() -> { start.await(); return submit(); });
            start.countDown();

            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS),
                    "submits concorrentes do mesmo type devem colapsar no mesmo jobId");
        } finally {
            pool.shutdownNow();
        }
    }

    private String submit() throws Exception {
        MvcResult r = mvc.perform(post("/jobs/sf-test"))
                .andExpect(status().isAccepted()).andReturn();
        return r.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
    }
}
