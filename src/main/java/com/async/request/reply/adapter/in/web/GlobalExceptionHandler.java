package com.async.request.reply.adapter.in.web;

import com.async.request.reply.adapter.in.web.mapper.JobProblemMapper;
import com.async.request.reply.core.exception.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        JobException jobException = findJobException(ex);
        if (jobException == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request body.");
            return ResponseEntity.badRequest().body(problem);
        }

        ProblemDetail problem = problemMapper.toProblemDetail(jobException);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private JobException findJobException(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof JobException jobException) {
                return jobException;
            }
            throwable = throwable.getCause();
        }
        return null;
    }
}
