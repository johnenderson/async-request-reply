package com.async.request.reply.adapter.in.web.dto;

import java.util.List;

public record JobResultResponse(
        String jobId,
        List<?> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
