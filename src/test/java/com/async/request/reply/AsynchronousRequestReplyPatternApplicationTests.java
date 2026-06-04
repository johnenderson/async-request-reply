package com.async.request.reply;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class AsynchronousRequestReplyPatternApplicationTests {

    @Autowired
    WebApplicationContext wac;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    // -------------------------------------------------------------------------
    // 1. POST /jobs — 202 + Location + Retry-After
    // -------------------------------------------------------------------------
    @Test
    void submitReturns202WithRequiredHeaders() throws Exception {
        mvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"test\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.statusUrl").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // 2. Idempotency-Key — mesmo job retornado em chamadas repetidas
    // -------------------------------------------------------------------------
    @Test
    void idempotencyKeyReturnsSameJob() throws Exception {
        String body = "{\"type\":\"idempotent-test\"}";

        MvcResult first = mvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-abc-123")
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();

        MvcResult second = mvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-abc-123")
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();

        String firstId  = first.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
        String secondId = second.getResponse().getContentAsString().replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
        assert firstId.equals(secondId) : "Idempotency-Key must return the same jobId";
    }

    // -------------------------------------------------------------------------
    // 3. GET /jobs/{id}/status — Retry-After + Expires + campos obrigatórios
    // -------------------------------------------------------------------------
    @Test
    void statusEndpointReturnsRequiredHeadersAndFields() throws Exception {
        MvcResult post = mvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"test\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        String location = post.getResponse().getHeader("Location");

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("Expires"))
                .andExpect(jsonPath("$.status").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.lastUpdatedAt").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // 4. Job concluído → 303 See Other para /result
    // -------------------------------------------------------------------------
    @Test
    void completedJobRedirectsWith303ToResult() throws Exception {
        MvcResult post = mvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"test\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        String location = post.getResponse().getHeader("Location");
        String jobId = post.getResponse().getContentAsString()
                .replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        Thread.sleep(6_000); // aguarda processamento (~5s)

        mvc.perform(get(location))
                .andExpect(status().isSeeOther())   // 303
                .andExpect(header().string("Location", containsString("/jobs/" + jobId + "/result")));
    }

    // -------------------------------------------------------------------------
    // 5. GET /jobs/{id}/result — retorna resultado após conclusão
    // -------------------------------------------------------------------------
    @Test
    void resultEndpointReturnsPayloadAfterCompletion() throws Exception {
        MvcResult post = mvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"test\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        String jobId = post.getResponse().getContentAsString()
                .replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        Thread.sleep(6_000);

        mvc.perform(get("/jobs/{id}/result", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // 6. DELETE /jobs/{id}/status — cancela job → 410 Gone no status
    // -------------------------------------------------------------------------
    @Test
    void cancelJobReturns202ThenStatusIsGone() throws Exception {
        MvcResult post = mvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"cancel-test\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        String jobId = post.getResponse().getContentAsString()
                .replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");

        mvc.perform(delete("/jobs/{id}/status", jobId))
                .andExpect(status().isAccepted()); // 202

        mvc.perform(get("/jobs/{id}/status", jobId))
                .andExpect(status().isGone()); // 410
    }

    // -------------------------------------------------------------------------
    // 7. Job inexistente → 404
    // -------------------------------------------------------------------------
    @Test
    void unknownJobReturns404() throws Exception {
        mvc.perform(get("/jobs/non-existent-id/status"))
                .andExpect(status().isNotFound());
    }
}
