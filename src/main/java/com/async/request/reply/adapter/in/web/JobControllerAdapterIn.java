package com.async.request.reply.adapter.in.web;

import com.async.request.reply.adapter.in.web.mapper.JobProblemMapper;
import com.async.request.reply.adapter.in.web.mapper.JobResponseMapper;
import com.async.request.reply.adapter.in.web.uribuilder.JobUriBuilder;
import com.async.request.reply.core.exception.InvalidJobRequestException;
import com.async.request.reply.core.port.in.CancelJobPortIn;
import com.async.request.reply.core.port.in.GetJobResultPortIn;
import com.async.request.reply.core.port.in.GetJobStatusPortIn;
import com.async.request.reply.core.port.in.SubmitJobPortIn;
import com.async.request.reply.core.result.JobResultView;
import com.async.request.reply.core.result.JobStatusView;
import com.async.request.reply.core.result.SubmittedJob;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Adapter in (web): HTTP boundary fino. Delega cada operação para um use case
 * dedicado e mapeia o domain outcome retornado para uma HTTP response. Nenhuma
 * decisão de negócio mora aqui — apenas status codes, headers e URI building.
 */
@RestController
@RequestMapping("/jobs")
public class JobControllerAdapterIn {

    private static final String TYPE_PATTERN = "[A-Za-z0-9._-]+";

    /** Teto do tamanho de página, para proteger latência/memória independente do cliente. */
    private static final int MAX_PAGE_SIZE = 200;

    private final SubmitJobPortIn submitJob;
    private final GetJobStatusPortIn getJobStatus;
    private final GetJobResultPortIn getJobResult;
    private final CancelJobPortIn cancelJob;
    private final JobUriBuilder uris;
    private final JobProblemMapper problemMapper;
    private final JobResponseMapper responseMapper;

    public JobControllerAdapterIn(SubmitJobPortIn submitJob,
                                  GetJobStatusPortIn getJobStatus,
                                  GetJobResultPortIn getJobResult,
                                  CancelJobPortIn cancelJob,
                                  JobUriBuilder uris,
                                  JobProblemMapper problemMapper,
                                  JobResponseMapper responseMapper) {
        this.submitJob = submitJob;
        this.getJobStatus = getJobStatus;
        this.getJobResult = getJobResult;
        this.cancelJob = cancelJob;
        this.uris = uris;
        this.problemMapper = problemMapper;
        this.responseMapper = responseMapper;
    }

    // POST /jobs/{type} — materializa o job daquele tipo; filtros ficam no /result
    @PostMapping("/{type}")
    public ResponseEntity<?> submit(
            @PathVariable String type,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        validateType(type);
        SubmittedJob submitted = submitJob.execute(type, idempotencyKey);
        URI statusUri = uris.status(submitted.jobId());

        return ResponseEntity.accepted()
                .location(statusUri)
                .header("Retry-After", String.valueOf(submitted.retryAfterSeconds()))
                .body(responseMapper.toSubmittedJobResponse(submitted, statusUri));
    }

    private void validateType(String type) {
        if (type == null || type.isBlank() || !type.matches(TYPE_PATTERN)) {
            throw new InvalidJobRequestException(
                    "O path variable 'type' deve usar apenas letras, números, ponto, hífen ou underscore.");
        }
    }

    // GET /jobs/{id}/status
    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id) {
        return switch (getJobStatus.execute(id)) {

            case JobStatusView.InProgress v -> ResponseEntity.ok()
                    .header("Retry-After", String.valueOf(v.retryAfterSeconds()))
                    .header("Expires", v.expiresAt().toString())
                    .body(responseMapper.toStatusResponse(v));

            case JobStatusView.Completed(var expiresAt) -> ResponseEntity.status(HttpStatus.SEE_OTHER) // 303
                    .location(uris.result(id))
                    .header("Expires", expiresAt.toString())
                    .build();

            case JobStatusView.Failed(var error) -> {
                ProblemDetail problem = problemMapper.toProblemDetail(error); // domínio → HTTP
                problem.setInstance(uris.status(id));
                yield ResponseEntity.status(problem.getStatus()).body(problem); // 422
            }

            case JobStatusView.Cancelled ignored -> ResponseEntity.status(HttpStatus.GONE).build(); // 410
            case JobStatusView.NotFound ignored -> ResponseEntity.notFound().build();               // 404
        };
    }

    // GET /jobs/{id}/result?page=&size=
    @GetMapping("/{id}/result")
    public ResponseEntity<?> result(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE); // 1..teto de página

        return switch (getJobResult.execute(id, safePage, safeSize)) {
            case JobResultView.Found v -> ResponseEntity.ok(responseMapper.toResultResponse(v));
            case JobResultView.NotCompleted v -> ResponseEntity.status(HttpStatus.CONFLICT) // 409
                    .body(responseMapper.toNotCompletedResponse(v));
            case JobResultView.NotFound ignored -> ResponseEntity.notFound().build();       // 404
        };
    }

    // DELETE /jobs/{id}/status
    @DeleteMapping("/{id}/status")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        return switch (cancelJob.execute(id)) {
            case CANCELLED -> ResponseEntity.accepted().build();                   // 202
            case ALREADY_TERMINAL -> ResponseEntity.status(HttpStatus.CONFLICT).build();  // 409
            case NOT_FOUND -> ResponseEntity.notFound().build();                   // 404
        };
    }
}
