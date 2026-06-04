package com.async.request.reply;

import com.async.request.reply.spi.JobHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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
        JobHandler<Map<String, Object>, List<String>> slowSfHandler() {
            return new JobHandler<>() {
                public String type() { return "sf-test"; }
                public List<String> handle(Map<String, Object> in) {
                    try { GATE.get().await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return List.of("ativos", "inativos");
                }
            };
        }
    }

    @Test
    void sameTypeWhileActiveReturnsSameJobId() throws Exception {
        // payloads diferentes — não importa: a chave é só o type
        String id1 = submit("{\"type\":\"sf-test\",\"payload\":{\"filtro\":\"ativos\"}}");
        String id2 = submit("{\"type\":\"sf-test\",\"payload\":{\"filtro\":\"inativos\"}}");

        assertEquals(id1, id2, "mesmo type em andamento deve retornar o mesmo jobId");
    }

    private String submit(String body) throws Exception {
        MvcResult r = mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn();
        return r.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
    }
}
