package com.async.request.reply.core.port.out;

/**
 * Outbound port que deriva, no servidor, uma chave determinística de coalescing
 * a partir do request. Permite que consumidores
 * independentes — que não se conhecem — colapsem requests equivalentes no mesmo
 * job em andamento, sem precisar enviar nenhuma chave.
 */
public interface CoalescingKeyPortOut {

    String keyFor(String type);
}
