package com.async.request.reply.adapter.in.web;

import com.async.request.reply.adapter.in.web.mapper.JobProblemMapper;
import com.async.request.reply.adapter.in.web.mapper.JobResponseMapper;
import com.async.request.reply.adapter.in.web.uribuilder.JobUriBuilder;
import com.async.request.reply.core.port.in.CancelJobPortIn;
import com.async.request.reply.core.port.in.GetJobResultPortIn;
import com.async.request.reply.core.port.in.GetJobStatusPortIn;
import com.async.request.reply.core.port.in.SubmitJobPortIn;
import com.async.request.reply.core.result.JobResultPage;
import com.async.request.reply.core.result.JobResultView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice do adapter web: só o controller, com os ports mockados. Verifica o
 * mapeamento protocolo → use case (validação e limites), sem subir contexto.
 */
@DisplayName("JobControllerAdapterIn")
class JobControllerAdapterInTest {

    private final SubmitJobPortIn submitJob = mock(SubmitJobPortIn.class);
    private final GetJobStatusPortIn getJobStatus = mock(GetJobStatusPortIn.class);
    private final GetJobResultPortIn getJobResult = mock(GetJobResultPortIn.class);
    private final CancelJobPortIn cancelJob = mock(CancelJobPortIn.class);
    private final JobProblemMapper problemMapper = new JobProblemMapper();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JobControllerAdapterIn controller = new JobControllerAdapterIn(submitJob, getJobStatus,
                getJobResult, cancelJob, new JobUriBuilder(), problemMapper, new JobResponseMapper());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(problemMapper))
                .build();
    }

    @ParameterizedTest(name = "type=[{0}]")
    @DisplayName("rejeita type que não serve como chave/rota, sem chamar o use case")
    @ValueSource(strings = {"com espaco", "com%20encoded", "acentuação", "com*curinga"})
    void submit_should_return_400_when_type_is_invalid(String invalidType) throws Exception {
        mvc.perform(post("/jobs/{type}", invalidType)).andExpect(status().isBadRequest());

        verify(submitJob, org.mockito.Mockito.never()).execute(anyString(), anyString());
    }

    @ParameterizedTest(name = "page={0}, size={1} → page={2}, size={3}")
    @DisplayName("normaliza paginação antes de chegar ao use case (teto protege latência/memória)")
    @CsvSource({
            "0,   20,   0, 20",
            "-5,  20,   0, 20",
            "2,   0,    2, 1",
            "2,   -1,   2, 1",
            "2,   9999, 2, 200"
    })
    void result_should_clamp_page_and_size(int page, int size, int expectedPage, int expectedSize)
            throws Exception {
        when(getJobResult.execute(anyString(), anyInt(), anyInt()))
                .thenReturn(new JobResultView.Found("job-1", new JobResultPage(List.of(), 0, 20, 0, 0)));

        mvc.perform(get("/jobs/{id}/result", "job-1")
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> pageArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> sizeArg = ArgumentCaptor.forClass(Integer.class);
        verify(getJobResult).execute(anyString(), pageArg.capture(), sizeArg.capture());
        assertThat(pageArg.getValue()).isEqualTo(expectedPage);
        assertThat(sizeArg.getValue()).isEqualTo(expectedSize);
    }

    @Test
    @DisplayName("cancela pela rota canônica e pelo alias de compatibilidade")
    void cancel_should_accept_both_routes() throws Exception {
        when(cancelJob.execute("job-1")).thenReturn(com.async.request.reply.core.enums.CancelResult.CANCELLED);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/jobs/{id}", "job-1")).andExpect(status().isAccepted());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/jobs/{id}/status", "job-1")).andExpect(status().isAccepted());
    }
}
