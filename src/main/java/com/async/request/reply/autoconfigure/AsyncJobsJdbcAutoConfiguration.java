package com.async.request.reply.autoconfigure;

import com.async.request.reply.adapter.out.persistence.jdbc.JdbcJobRepositoryAdapterOut;
import com.async.request.reply.core.port.out.JobRepositoryPortOut;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Auto-configuration do storage relacional — Aurora PostgreSQL (ADR 0004).
 *
 * <p>O {@code DataSource} é do projeto consumidor: a lib não configura pool nem
 * datasource, ela apenas usa o que a aplicação já tem para falar com o banco. O
 * esquema também é do consumidor, aplicado com a ferramenta de migração dele —
 * o DDL de referência está em {@code async-jobs-schema.sql}.</p>
 */
@AutoConfiguration
@ConditionalOnClass(JdbcClient.class)
@ConditionalOnProperty(name = "async-jobs.storage", havingValue = "jdbc", matchIfMissing = true)
public class AsyncJobsJdbcAutoConfiguration {

    /**
     * Só cria o {@link JdbcClient} se a aplicação ainda não tiver um — quando o
     * consumidor usa o starter de JDBC do Boot, ele já vem pronto.
     */
    @Bean
    @ConditionalOnMissingBean
    JdbcClient asyncJobsJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    JobRepositoryPortOut jobRepositoryPortOut(JdbcClient jdbcClient, Clock clock) {
        return new JdbcJobRepositoryAdapterOut(jdbcClient, clock);
    }
}
