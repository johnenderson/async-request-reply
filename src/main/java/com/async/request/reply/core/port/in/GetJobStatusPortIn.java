package com.async.request.reply.core.port.in;

/**
 * Inbound port: resolver o status atual de um job em um domain outcome.
 */
public interface GetJobStatusPortIn {

    JobStatusView execute(String id);
}
