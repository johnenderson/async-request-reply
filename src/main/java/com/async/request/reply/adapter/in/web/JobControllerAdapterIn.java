package com.async.request.reply.adapter.in.web;

import com.async.request.reply.adapter.in.web.mapper.JobProblemMapper;
import com.async.request.reply.adapter.in.web.mapper.JobResponseMapper;
import com.async.request.reply.adapter.in.web.uribuilder.JobUriBuilder;
import com.async.request.reply.core.exception.InvalidJobRequestException;
import com.async.request.reply.core.port.in.CancelJobPortIn;
import com.async.request.reply.core.port.in.GetJobStatusPortIn;
import com.async.request.reply.core.port.in.SubmitJobPortIn;
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
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Adapter in (web): HTTP boundary fino. Delega cada operação para um use case
 * dedicado e mapeia o domain outcome retornado para uma HTTP response. Nenhuma
 * decisão de negócio mora aqui — apenas status codes, headers e URI building.
 *
 * <p>Não existe endpoint de resultado: a lib controla execução, não serve dados
 * (ADR 0004). O status apenas informa quando o job terminou; o cliente lê o
 * resultado no endpoint de domínio dele.</p>
 */
@RestController
@RequestMapping("/jobs")
public class JobControllerAdapterIn {

    private static final String TYPE_PATTERN = "[A-Za-z0-9._-]+";

    private final SubmitJobPortIn submitJob;
    private final GetJobStatusPortIn getJobStatus;
    private final CancelJobPortIn cancelJob;
    private final JobUriBuilder uris;
    private final JobProblemMapper problemMapper;
    private final JobResponseMapper responseMapper;

    public JobControllerAdapterIn(SubmitJobPortIn submitJob,
                                  GetJobStatusPortIn getJobStatus,
                                  CancelJobPortIn cancelJob,
                                  JobUriBuilder uris,
                                  JobProblemMapper problemMapper,
                                  JobResponseMapper responseMapper) {
        this.submitJob = submitJob;
        this.getJobStatus = getJobStatus;
        this.cancelJob = cancelJob;
        this.uris = uris;
        this.problemMapper = problemMapper;
        this.responseMapper = responseMapper;
    }

    // POST /jobs/{type} — garante que o trabalho daquele tipo seja executado
    @PostMapping("/{type}")
    public ResponseEntity<?> submit(
            @PathVariable String type,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Cache-Control", required = false) String cacheControl) {

        validateType(type);
        SubmittedJob submitted = submitJob.execute(type, idempotencyKey, noCache(cacheControl));
        URI statusUri = uris.status(submitted.jobId());

        return ResponseEntity.accepted()
                .location(statusUri)
                .header("Retry-After", String.valueOf(submitted.retryAfterSeconds()))
                .body(responseMapper.toSubmittedJobResponse(
                        submitted, statusUri, uris.events(submitted.jobId())));
    }

    /**
     * {@code Cache-Control: no-cache} (RFC 9111) é a forma padrão de o cliente
     * dizer "não me sirva dado guardado": aqui ela ignora a janela de frescor e
     * força uma carga nova. Só a diretiva exata conta — {@code max-age=0} e
     * {@code no-store} não pedem revalidação de carga.
     */
    private static boolean noCache(String cacheControl) {
        if (cacheControl == null) {
            return false;
        }
        for (String directive : cacheControl.split(",")) {
            if ("no-cache".equalsIgnoreCase(directive.trim())) {
                return true;
            }
        }
        return false;
    }

    /** HTTP-date (IMF-fixdate, RFC 9110): "Tue, 03 Jun 2026 22:00:00 GMT". */
    private static String httpDate(Instant instant) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atZone(ZoneOffset.UTC));
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
                    .header("Expires", httpDate(v.expiresAt()))
                    .body(responseMapper.toStatusResponse(v.body()));

            // terminais: o recurso de status segue legivel, sem Retry-After
            case JobStatusView.Completed v -> terminal(v.body(), v.expiresAt());
            case JobStatusView.Cancelled v -> terminal(v.body(), v.expiresAt());

            case JobStatusView.Failed(var error) -> {
                ProblemDetail problem = problemMapper.toProblemDetail(error); // domínio → HTTP
                problem.setInstance(uris.status(id));
                yield ResponseEntity.status(problem.getStatus()).body(problem); // 422
            }

            case JobStatusView.NotFound _ -> ResponseEntity.notFound().build();               // 404
        };
    }

    private ResponseEntity<?> terminal(
            com.async.request.reply.core.result.JobStatusSnapshot body, Instant expiresAt) {
        return ResponseEntity.ok()
                .header("Expires", httpDate(expiresAt))
                .body(responseMapper.toStatusResponse(body));
    }

    // DELETE /jobs/{id} (canônico) e /jobs/{id}/status (compat com o contrato original)
    @DeleteMapping({"/{id}", "/{id}/status"})
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        return switch (cancelJob.execute(id)) {
            case CANCELLED -> ResponseEntity.accepted().build();                   // 202
            case ALREADY_TERMINAL -> ResponseEntity.status(HttpStatus.CONFLICT).build();  // 409
            case NOT_FOUND -> ResponseEntity.notFound().build();                   // 404
        };
    }
}
