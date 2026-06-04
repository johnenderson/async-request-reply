package com.async.request.reply.adapter.in.web;

import com.async.request.reply.core.port.in.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * Adapter in (web): HTTP boundary fino. Delega cada operação para um use case
 * dedicado e mapeia o domain outcome retornado para uma HTTP response. Nenhuma
 * decisão de negócio mora aqui — apenas status codes, headers e URI building.
 */
@RestController
@RequestMapping("/jobs")
public class JobControllerAdapterIn {

    private final SubmitJobPortIn submitJob;
    private final GetJobStatusPortIn getJobStatus;
    private final GetJobResultPortIn getJobResult;
    private final CancelJobPortIn cancelJob;
    private final JobUriBuilder uris;
    private final JobProblemMapper problemMapper;

    public JobControllerAdapterIn(SubmitJobPortIn submitJob,
                                  GetJobStatusPortIn getJobStatus,
                                  GetJobResultPortIn getJobResult,
                                  CancelJobPortIn cancelJob,
                                  JobUriBuilder uris,
                                  JobProblemMapper problemMapper) {
        this.submitJob = submitJob;
        this.getJobStatus = getJobStatus;
        this.getJobResult = getJobResult;
        this.cancelJob = cancelJob;
        this.uris = uris;
        this.problemMapper = problemMapper;
    }

    // POST /jobs
    @PostMapping
    public ResponseEntity<Map<String, String>> submit(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        SubmittedJob submitted = submitJob.execute(payload, idempotencyKey);
        URI statusUri = uris.status(submitted.jobId());

        return ResponseEntity.accepted()
                .location(statusUri)
                .header("Retry-After", String.valueOf(submitted.retryAfterSeconds()))
                .body(Map.of("jobId", submitted.jobId(), "statusUrl", statusUri.toString()));
    }

    // GET /jobs/{id}/status
    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id) {
        return switch (getJobStatus.execute(id)) {

            case JobStatusView.InProgress v -> ResponseEntity.ok()
                    .header("Retry-After", String.valueOf(v.retryAfterSeconds()))
                    .header("Expires", v.expiresAt().toString())
                    .body(v.body());

            case JobStatusView.Completed v -> ResponseEntity.status(HttpStatus.SEE_OTHER) // 303
                    .location(uris.result(id))
                    .header("Expires", v.expiresAt().toString())
                    .build();

            case JobStatusView.Failed v -> {
                ProblemDetail problem = problemMapper.toProblemDetail(v.error()); // domínio → HTTP
                problem.setInstance(uris.status(id));
                yield ResponseEntity.status(problem.getStatus()).body(problem); // 422
            }

            case JobStatusView.Cancelled ignored -> ResponseEntity.status(HttpStatus.GONE).build(); // 410
            case JobStatusView.NotFound ignored -> ResponseEntity.notFound().build();               // 404
        };
    }

    // GET /jobs/{id}/result
    @GetMapping("/{id}/result")
    public ResponseEntity<?> result(@PathVariable String id) {
        return switch (getJobResult.execute(id)) {
            case JobResultView.Found v -> ResponseEntity.ok(Map.of("jobId", v.jobId(), "result", v.result()));
            case JobResultView.NotCompleted v -> ResponseEntity.status(HttpStatus.CONFLICT) // 409
                    .body(Map.of("error", "Job is not completed yet", "status", v.status()));
            case JobResultView.NotFound ignored -> ResponseEntity.notFound().build();       // 404
        };
    }

    // DELETE /jobs/{id}/status
    @DeleteMapping("/{id}/status")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        return switch (cancelJob.execute(id)) {
            case CANCELLED        -> ResponseEntity.accepted().build();                   // 202
            case ALREADY_TERMINAL -> ResponseEntity.status(HttpStatus.CONFLICT).build();  // 409
            case NOT_FOUND        -> ResponseEntity.notFound().build();                   // 404
        };
    }
}
