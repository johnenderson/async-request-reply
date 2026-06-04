package com.async.request.reply.adapter.out.coalescing;

import com.async.request.reply.core.port.out.CoalescingKeyPortOut;

/**
 * Adapter out: chave de coalescing baseada apenas no {@code type} (default).
 *
 * <p>Adequado quando o job é parameterless — sempre produz o mesmo resultado
 * para um type (ex: "listar todas as contas"); a filtragem é feita na leitura
 * do resultado, não na submissão. Selecionável via {@code async-jobs.coalesce-key=type}.
 */
public class TypeCoalescingKeyAdapterOut implements CoalescingKeyPortOut {

    @Override
    public String keyFor(String type, Object payload) {
        return type;
    }
}
