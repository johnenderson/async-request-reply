package com.async.request.reply.core.port.in;

import com.async.request.reply.core.result.SubmittedJob;

/**
 * Inbound port: submeter um novo job para processamento assíncrono.
 * O {@code type} seleciona a rotina (handler) registrada no projeto consumidor.
 *
 * <p>O single-flight é automático e derivado no servidor conforme a estratégia
 * configurada (por padrão, o próprio {@code type}): enquanto houver um job ativo
 * para uma chave equivalente, novos submits retornam
 * o job em andamento — sem o consumidor precisar enviar nenhuma chave.
 */
public interface SubmitJobPortIn {

    SubmittedJob execute(String type, String idempotencyKey);
}
