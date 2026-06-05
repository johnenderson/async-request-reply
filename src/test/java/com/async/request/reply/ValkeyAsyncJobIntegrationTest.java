package com.async.request.reply;

import com.async.request.reply.spi.AsyncJobHandler;
import com.async.request.reply.spi.JobContext;
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

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "async-jobs.coalesce-in-flight=false",
        "async-jobs.result-ttl=PT10M"
})
class ValkeyAsyncJobIntegrationTest extends ValkeyContainerTestSupport {

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JobReporter reporter;

    @Autowired
    CapturingAsyncRoutine routine;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        routine.clear();
    }

    @Test
    void asyncRoutineCompletesThroughReporterAndReturnsPagedResultFromValkey() throws Exception {
        MvcResult submitted = mvc.perform(post("/jobs/tc-async"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andReturn();

        String jobId = jobIdFrom(submitted);
        String statusUrl = submitted.getResponse().getHeader("Location");

        assertEquals(jobId, routine.awaitJobId(), "handler async deve receber o jobId gerado");

        mvc.perform(get(statusUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is(jobId)))
                .andExpect(jsonPath("$.status", is("PROCESSING")));

        reporter.complete(jobId, List.of("page-1", "page-2", "page-3"));

        mvc.perform(get(statusUrl))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", containsString("/jobs/" + jobId + "/result")));

        mvc.perform(get("/jobs/{id}/result", jobId)
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is(jobId)))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0]", is("page-3")))
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(2)));
    }

    private static String jobIdFrom(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
    }

    @TestConfiguration
    static class TestHandlers {

        @Bean
        CapturingAsyncRoutine capturingAsyncRoutine() {
            return new CapturingAsyncRoutine();
        }
    }

    static class CapturingAsyncRoutine implements AsyncJobHandler {

        private final BlockingQueue<String> jobIds = new ArrayBlockingQueue<>(1);

        @Override
        public String type() {
            return "tc-async";
        }

        @Override
        public void start(JobContext ctx) {
            jobIds.offer(ctx.jobId());
        }

        String awaitJobId() throws InterruptedException {
            String jobId = jobIds.poll(5, TimeUnit.SECONDS);
            assertNotNull(jobId, "handler async nao foi chamado");
            return jobId;
        }

        void clear() {
            jobIds.clear();
        }
    }
}
