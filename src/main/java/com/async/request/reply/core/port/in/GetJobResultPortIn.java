package com.async.request.reply.core.port.in;

import com.async.request.reply.core.result.JobResultView;

/**
 * Inbound port: recuperar o resultado (paginado) de um job concluído.
 */
public interface GetJobResultPortIn {

    JobResultView execute(String id, int page, int size);
}
