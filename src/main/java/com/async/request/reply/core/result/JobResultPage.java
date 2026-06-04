package com.async.request.reply.core.result;

import java.util.List;

/**
 * Resultado paginado do caso de uso de consulta de resultado de job.
 */
public record JobResultPage(
        List<?> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static JobResultPage of(Object result, int page, int size) {
        List<?> all = switch (result) {
            case null -> List.of();
            case List<?> list -> list;
            default -> List.of(result);
        };
        int safeSize = Math.max(size, 1);
        int safePage = Math.max(page, 0);
        int from = (int) Math.min((long) safePage * safeSize, all.size());
        int to = (int) Math.min((long) from + safeSize, all.size());
        List<?> slice = all.subList(from, to);
        int totalPages = (int) Math.ceil((double) all.size() / safeSize);
        return new JobResultPage(slice, safePage, safeSize, all.size(), totalPages);
    }
}
