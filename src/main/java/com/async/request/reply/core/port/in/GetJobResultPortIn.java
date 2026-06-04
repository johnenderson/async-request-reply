package com.async.request.reply.core.port.in;

/**
 * Inbound port: recuperar o resultado de um job concluído.
 */
public interface GetJobResultPortIn {

    JobResultView execute(String id);
}
