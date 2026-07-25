package com.async.request.reply;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Gate usado pelos handlers "lentos" dos testes para manter um job ativo de
 * forma determinística.
 *
 * <p>O timeout é uma <b>falha explícita</b>, nunca uma conclusão silenciosa: um
 * handler que voltasse a completar por expiração do gate faria o job terminar no
 * meio do teste e quebraria asserções de cancelamento/coalescing de um jeito
 * difícil de diagnosticar (foi o que acontecia com timeouts curtos quando a
 * máquina engasgava). A janela é generosa porque quem libera de verdade é o
 * {@code @AfterEach}.</p>
 */
final class TestGate {

    private static final long TIMEOUT_SECONDS = 60;

    private TestGate() {
    }

    static void await(CountDownLatch gate) {
        try {
            if (!gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "gate do teste nao foi liberado em " + TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido aguardando o gate do teste", e);
        }
    }
}
