package com.async.request.reply.core.port.in;

import com.async.request.reply.core.result.SubmittedJob;

/**
 * Inbound port: garantir que o trabalho daquele {@code type} seja executado. O
 * {@code type} seleciona a rotina (handler) registrada no projeto consumidor.
 *
 * <p>A submissão é idempotente no tempo: se já existe job ativo para o escopo
 * (single-flight) ou se uma carga concluiu dentro da janela de frescor, nenhum
 * trabalho novo é enfileirado e o job existente é devolvido — sem o consumidor
 * precisar enviar chave alguma.</p>
 */
public interface SubmitJobPortIn {

    SubmittedJob execute(String type, String idempotencyKey);

    /**
     * @param forceRefresh ignora a janela de frescor, forçando carga nova
     *                     ({@code Cache-Control: no-cache} na borda web). O
     *                     single-flight continua valendo: força carga, não
     *                     execução concorrente.
     */
    SubmittedJob execute(String type, String idempotencyKey, boolean forceRefresh);
}
