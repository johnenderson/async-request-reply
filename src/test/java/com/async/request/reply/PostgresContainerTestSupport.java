package com.async.request.reply;

import org.junit.jupiter.api.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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

    /**
     * O container é único para toda a suíte (singleton): subir um Postgres por
     * classe de teste multiplicaria o tempo sem aumentar o isolamento — cada
     * teste limpa a tabela no {@code @BeforeEach}.
     */
    public static DataSource dataSource() {
        return DATA_SOURCE;
    }

    /** Limpa a tabela entre testes: o container é compartilhado pela suíte. */
    public static void truncateJobs() {
        JdbcClient.create(DATA_SOURCE).sql("delete from async_jobs").update();
    }
}
