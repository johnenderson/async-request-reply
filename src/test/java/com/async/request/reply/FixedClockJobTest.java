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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O {@link Clock} é injetável justamente para isto: com um relógio fixo, os
 * timestamps persistidos e o header {@code Expires} passam a ser determinísticos,
 * sem depender de tempo real.
 */
@SpringBootTest(properties = {
        "async-jobs.retention=PT10M",
        "async-jobs.coalesce-in-flight=false",
        "async-jobs.recovery.enabled=false"
})
class FixedClockJobTest extends PostgresContainerTestSupport {

    static final Instant FIXED = Instant.parse("2026-06-03T22:00:00Z");

    static final AtomicReference<CountDownLatch> GATE = new AtomicReference<>(new CountDownLatch(0));

    @Autowired
    WebApplicationContext wac;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        truncateJobs();
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        GATE.set(new CountDownLatch(1));
    }

    @AfterEach
    void releaseGate() {
        GATE.get().countDown();
    }

    @TestConfiguration
    static class Config {

        @Bean
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }

        @Bean
        JobHandler clockHandler() {
            return new JobHandler() {
                public String type() { return "clock-test"; }
                public void handle() {
                    TestGate.await(GATE.get());
                }
            };
        }
    }

    @Test
    void persistedTimestampsAndExpiresComeFromTheInjectedClock() throws Exception {
        MvcResult submitted = mvc.perform(post("/jobs/clock-test"))
                .andExpect(status().isAccepted()).andReturn();
        String statusUrl = submitted.getResponse().getHeader("Location");

        String expectedExpires = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(FIXED.plus(Duration.ofMinutes(10)).atZone(ZoneOffset.UTC));

        mvc.perform(get(statusUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt", is(FIXED.toString())))
                .andExpect(jsonPath("$.lastUpdatedAt", is(FIXED.toString())))
                .andExpect(header().string("Expires", expectedExpires));
    }
}
