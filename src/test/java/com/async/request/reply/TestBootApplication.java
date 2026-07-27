package com.async.request.reply;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Configuração de boot exclusiva dos testes: como o projeto é uma biblioteca
 * (sem {@code @SpringBootApplication} no main), os {@code @SpringBootTest}
 * encontram esta classe ao subir pacotes e habilitam as auto-configurations
 * registradas em {@code META-INF/spring/...AutoConfiguration.imports}.
 *
 * <p>Também faz o papel do projeto consumidor ao fornecer o {@link DataSource}:
 * a lib não configura pool nem ativa {@code DataSourceAutoConfiguration} (ADR
 * 0004), então quem sobe contexto precisa entregar a conexão — aqui, o Postgres
 * de {@link PostgresContainerTestSupport}.</p>
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
class TestBootApplication {

    @Bean
    DataSource dataSource() {
        return PostgresContainerTestSupport.dataSource();
    }
}
