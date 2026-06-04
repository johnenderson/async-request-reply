package com.async.request.reply.adapter.out.coalescing;

import com.async.request.reply.core.port.out.CoalescingKeyPortOut;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Adapter out: chave de coalescing canônica = {@code type:sha256(payload)}.
 *
 * <p>Use quando o payload <b>altera o resultado</b>: requests do mesmo type mas
 * com payloads diferentes geram jobs distintos; apenas requests idênticos
 * coalescem. O payload é serializado com chaves de Map ordenadas (payloads
 * logicamente iguais → mesma chave). Selecionável via {@code async-jobs.coalesce-key=payload}.
 */
public class PayloadCoalescingKeyAdapterOut implements CoalescingKeyPortOut {

    private final ObjectMapper objectMapper;

    public PayloadCoalescingKeyAdapterOut(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String keyFor(String type, Object payload) {
        try {
            byte[] canonical = objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(payload);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return type + ":" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return type + ":" + (payload == null ? "null" : Integer.toHexString(payload.hashCode()));
        }
    }
}
