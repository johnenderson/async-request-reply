package com.async.request.reply;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Base dos testes de integração: Valkey real via Testcontainers (core 2.x).
 *
 * <p>Usa o padrão "singleton container" (start estático) em vez das anotações
 * {@code @Testcontainers/@Container} — no Testcontainers 2.x o módulo
 * junit-jupiter não existe; o container é reutilizado por todas as classes e
 * encerrado no shutdown da JVM (Ryuk).
 */
@Tag("integration")
abstract class ValkeyContainerTestSupport {

    static final GenericContainer<?> VALKEY =
            new GenericContainer<>("valkey/valkey:8.1.8-alpine")
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections tcp.*\\n", 1));

    static {
        VALKEY.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }
}
