package com.async.request.reply;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Configuração de boot exclusiva dos testes: como o projeto é uma biblioteca
 * (sem {@code @SpringBootApplication} no main), os {@code @SpringBootTest}
 * encontram esta classe ao subir pacotes e habilitam as auto-configurations
 * registradas em {@code META-INF/spring/...AutoConfiguration.imports}.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
class TestBootApplication {
}
