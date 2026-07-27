package com.async.request.reply.adapter.in.web;

import com.async.request.reply.adapter.in.web.mapper.JobProblemMapper;
import com.async.request.reply.adapter.in.web.mapper.JobResponseMapper;
import com.async.request.reply.adapter.in.web.uribuilder.JobUriBuilder;
import com.async.request.reply.core.enums.CancelResult;
import com.async.request.reply.core.port.in.CancelJobPortIn;
import com.async.request.reply.core.port.in.GetJobStatusPortIn;
import com.async.request.reply.core.port.in.SubmitJobPortIn;
import com.async.request.reply.core.result.SubmittedJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice do adapter web: só o controller, com os ports mockados. Verifica o
 * mapeamento protocolo → use case, sem subir contexto.
 */
@DisplayName("JobControllerAdapterIn")
class JobControllerAdapterInTest {

    private final SubmitJobPortIn submitJob = mock(SubmitJobPortIn.class);
    private final GetJobStatusPortIn getJobStatus = mock(GetJobStatusPortIn.class);
    private final CancelJobPortIn cancelJob = mock(CancelJobPortIn.class);
    private final JobProblemMapper problemMapper = new JobProblemMapper();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JobControllerAdapterIn controller = new JobControllerAdapterIn(submitJob, getJobStatus,
                cancelJob, new JobUriBuilder(), problemMapper, new JobResponseMapper());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(problemMapper))
                .build();
    }

    @ParameterizedTest(name = "type=[{0}]")
    @DisplayName("rejeita type que não serve como chave/rota, sem chamar o use case")
    @ValueSource(strings = {"com espaco", "com%20encoded", "acentuação", "com*curinga"})
    void submit_should_return_400_when_type_is_invalid(String invalidType) throws Exception {
        mvc.perform(post("/jobs/{type}", invalidType)).andExpect(status().isBadRequest());

        verify(submitJob, never()).execute(anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("o 202 anuncia as duas formas de acompanhar: stream de eventos e status")
    void submit_should_advertise_events_and_status_urls() throws Exception {
        when(submitJob.execute(anyString(), any(), anyBoolean())).thenReturn(new SubmittedJob("job-1", 5));

        mvc.perform(post("/jobs/{type}", "relatorio"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId", containsString("job-1")))
                .andExpect(jsonPath("$.statusUrl", containsString("/jobs/job-1/status")))
                .andExpect(jsonPath("$.eventsUrl", containsString("/jobs/job-1/events")));
    }

    @Test
    @DisplayName("Cache-Control: no-cache força carga nova, ignorando o dado quente")
    void submit_should_force_a_refresh_when_client_sends_no_cache() throws Exception {
        when(submitJob.execute(anyString(), any(), anyBoolean())).thenReturn(new SubmittedJob("job-1", 5));

        mvc.perform(post("/jobs/{type}", "relatorio").header("Cache-Control", "no-cache"))
                .andExpect(status().isAccepted());

        // o header e a forma padrao de o cliente dizer "nao me sirva cache"
        verify(submitJob).execute("relatorio", null, true);
    }

    @ParameterizedTest(name = "Cache-Control: [{0}]")
    @DisplayName("sem no-cache, o frescor decide — inclusive com outras diretivas")
    @ValueSource(strings = {"max-age=0", "no-store", "no-cache-mesmo-nao"})
    void submit_should_not_force_a_refresh_for_other_directives(String cacheControl) throws Exception {
        when(submitJob.execute(anyString(), any(), anyBoolean())).thenReturn(new SubmittedJob("job-1", 5));

        mvc.perform(post("/jobs/{type}", "relatorio").header("Cache-Control", cacheControl))
                .andExpect(status().isAccepted());

        verify(submitJob).execute("relatorio", null, false);
    }

    @Test
    @DisplayName("cancela pela rota canônica e pelo alias de compatibilidade")
    void cancel_should_accept_both_routes() throws Exception {
        when(cancelJob.execute("job-1")).thenReturn(CancelResult.CANCELLED);

        mvc.perform(delete("/jobs/{id}", "job-1")).andExpect(status().isAccepted());
        mvc.perform(delete("/jobs/{id}/status", "job-1")).andExpect(status().isAccepted());
    }
}
