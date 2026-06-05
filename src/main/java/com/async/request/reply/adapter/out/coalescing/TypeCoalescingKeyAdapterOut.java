package com.async.request.reply.adapter.out.coalescing;

import com.async.request.reply.core.port.out.CoalescingKeyPortOut;

/**
 * Adapter out: chave de coalescing baseada apenas no {@code type} (default).
 *
 * <p>Adequado ao contrato web default: o submit apenas materializa o resultado
 * base daquele tipo; filtros são aplicados na leitura do resultado.
 */
public class TypeCoalescingKeyAdapterOut implements CoalescingKeyPortOut {

    @Override
    public String keyFor(String type) {
        return type;
    }
}
