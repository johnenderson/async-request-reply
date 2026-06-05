package com.async.request.reply.core.port.out;

import com.async.request.reply.core.result.JobResultPage;

import java.util.List;

/**
 * Outbound port do resultado materializado em chunks. Os itens são anexados
 * incrementalmente e a paginação é nativa do storage (sem carregar tudo em
 * memória) — ex: Redis/Valkey LIST com RPUSH/LRANGE/LLEN.
 */
public interface JobResultStorePortOut {

    /** Anexa itens ao resultado do job (append incremental). */
    void append(String id, List<?> items);

    /** Página do resultado, lida diretamente do storage. */
    JobResultPage page(String id, int page, int size);
}
