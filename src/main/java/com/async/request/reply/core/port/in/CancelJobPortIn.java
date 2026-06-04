package com.async.request.reply.core.port.in;

import com.async.request.reply.core.enums.CancelResult;

/**
 * Inbound port: cancelar um job em execução.
 */
public interface CancelJobPortIn {

    CancelResult execute(String id);
}
