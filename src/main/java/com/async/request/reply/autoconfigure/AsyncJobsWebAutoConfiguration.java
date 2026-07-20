package com.async.request.reply.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Auto-configuration do adapter web (endpoints /jobs). Só ativa em aplicações
 * web servlet e pode ser desligada via {@code async-jobs.web.enabled=false} —
 * assim a lib pode ser usada apenas como motor (sem expor HTTP).
 *
 * <p>O subpacote {@code sse} fica fora do scan: o endpoint de eventos é opt-in
 * e registrado pela {@link AsyncJobsSseAutoConfiguration}.</p>
 */
@AutoConfiguration(after = AsyncJobsAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "async-jobs.web.enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "com.async.request.reply.adapter.in.web",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.async\\.request\\.reply\\.adapter\\.in\\.web\\.sse\\..*"))
public class AsyncJobsWebAutoConfiguration {
}
