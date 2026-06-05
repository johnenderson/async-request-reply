package com.async.request.reply.core.result;

import java.util.List;

/**
 * Página do resultado de um job. Construída pelo result store a partir de uma
 * leitura paginada nativa (LLEN/LRANGE) — não há slicing em memória.
 */
public record JobResultPage(
        List<?> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
