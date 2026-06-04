package com.async.request.reply.core.port.in;

import java.util.Map;

/**
 * Inbound port: submeter um novo job para processamento assíncrono.
 */
public interface SubmitJobPortIn {

    SubmittedJob execute(Map<String, Object> payload, String idempotencyKey);
}
