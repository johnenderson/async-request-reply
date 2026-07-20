package com.async.request.reply.adapter.in.web.mapper;

import com.async.request.reply.core.exception.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Adapter in (web): traduz uma {@link JobException} de domínio em um
 * {@link ProblemDetail} (RFC 9457). É o único ponto que conhece o mapeamento
 * ErrorType → HTTP status, mantendo o core livre de dependência web.
 */
@Component
public class JobProblemMapper {

    public ProblemDetail toProblemDetail(JobException ex) {
        HttpStatus status = switch (ex.errorType()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;            // 400
            // 422 para key reusada com request diferente, seguindo o draft
            // IETF do header Idempotency-Key
            case CONFLICT   -> HttpStatus.UNPROCESSABLE_CONTENT;  // 422
            case FAILED     -> HttpStatus.UNPROCESSABLE_CONTENT;  // 422
            case INTERNAL   -> HttpStatus.INTERNAL_SERVER_ERROR; // 500
        };
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(ex.getTitle());
        problem.setDetail(ex.getMessage());
        return problem;
    }
}
