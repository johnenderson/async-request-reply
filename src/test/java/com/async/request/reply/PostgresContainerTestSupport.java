package com.async.request.reply;

import org.junit.jupiter.api.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import javax.sql.DataSource;

/**
 * Base dos testes do storage JDBC: Postgres real via Testcontainers, com o
 * mesmo DDL que o consumidor aplica em produção
 * ({@code async-jobs-schema.sql}) — assim o esquema documentado e o esquema
 * testado nunca divergem.
 *
 * <p>Usa {@link GenericContainer} (como o suporte a Valkey) em vez do módulo
 * dedicado: no Testcontainers 2.x ele não existe nesta versão, e para subir um
 * Postgres bastam variáveis de ambiente e a porta mapeada.</p>
 */
@Tag("integration")
public abstract class PostgresContainerTestSupport {

    private static final String DATABASE = "asyncjobs";
    private static final String USER = "asyncjobs";
    private static final String PASSWORD = "asyncjobs";
    private static final int PORT = 5432;

    static final GenericContainer<?> POSTGRES =
            new GenericContainer<>("postgres:18.3-alpine")
                    .withExposedPorts(PORT)
                    .withEnv("POSTGRES_DB", DATABASE)
                    .withEnv("POSTGRES_USER", USER)
                    .withEnv("POSTGRES_PASSWORD", PASSWORD)
                    // a mensagem aparece duas vezes: fim do init e start definitivo
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl(), USER, PASSWORD);
        dataSource.setDriverClassName("org.postgresql.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("async-jobs-schema.sql")).execute(dataSource);
        DATA_SOURCE = dataSource;
    }

    public static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PORT) + "/" + DATABASE;
    }

    /** DataSource para testes de contrato do adapter, sem contexto Spring. */
    public static DataSource dataSource() {
        return DATA_SOURCE;
    }

    /** Usado pelos testes que sobem contexto (@SpringBootTest). */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("async-jobs.storage", () -> "jdbc");
        registry.add("spring.datasource.url", PostgresContainerTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }
}
