package com.async.request.reply.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration do adapter web (endpoints /jobs). Só ativa em aplicações
 * web servlet e pode ser desligada via {@code async-jobs.web.enabled=false} —
 * assim a lib pode ser usada apenas como motor (sem expor HTTP).
 */
@AutoConfiguration(after = AsyncJobsAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "async-jobs.web.enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("com.async.request.reply.adapter.in.web")
public class AsyncJobsWebAutoConfiguration {
}
