package com.async.request.reply.adapter.in.web;

import com.async.request.reply.core.exception.JobException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Adapter in (web): traduz as exceptions do domínio de jobs em Problem Details
 * (RFC 9457) via {@link JobProblemMapper}. Handler único que cobre toda a
 * hierarquia de {@link JobException} por polimorfismo.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final JobProblemMapper problemMapper;

    public GlobalExceptionHandler(JobProblemMapper problemMapper) {
        this.problemMapper = problemMapper;
    }

    @ExceptionHandler(JobException.class)
    public ResponseEntity<ProblemDetail> handleJobException(JobException ex) {
        ProblemDetail problem = problemMapper.toProblemDetail(ex);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
