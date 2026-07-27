package com.async.request.reply.adapter.in.web.mapper;

import com.async.request.reply.core.domain.JobFailure;
import com.async.request.reply.core.exception.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Adapter in (web): traduz erros de domínio em {@link ProblemDetail} (RFC 9457).
 * É o único ponto que conhece o mapeamento domínio → HTTP status, mantendo o
 * core livre de dependência web.
 */
@Component
public class JobProblemMapper {

    /** Erro de submissão (validação/idempotência) — vindo de uma exception de controle. */
    public ProblemDetail toProblemDetail(JobException ex) {
        HttpStatus status = switch (ex.errorType()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;            // 400
            case CONFLICT   -> HttpStatus.UNPROCESSABLE_CONTENT;  // 422
        };
        return problem(status, ex.getTitle(), ex.getMessage());
    }

    /** Falha terminal de um job (estado persistido, não exception) → 422. */
    public ProblemDetail toProblemDetail(JobFailure failure) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, failure.title(), failure.detail());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }
}
