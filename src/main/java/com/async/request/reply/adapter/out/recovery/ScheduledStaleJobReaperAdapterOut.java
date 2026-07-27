package com.async.request.reply.adapter.out.recovery;

import com.async.request.reply.config.AsyncJobsProperties;
import com.async.request.reply.core.service.StaleJobRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Adapter out: agenda a varredura do {@link StaleJobRecoveryService} num
 * scheduler próprio (thread daemon), sem exigir {@code @EnableScheduling} na
 * aplicação consumidora — uma biblioteca não deve ligar scheduling global.
 *
 * <p>Cada ciclo é envolvido em try/catch: uma exceção não tratada mataria o
 * agendamento silenciosamente e a recuperação pararia de funcionar.</p>
 */
public class ScheduledStaleJobReaperAdapterOut implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ScheduledStaleJobReaperAdapterOut.class);

    private final StaleJobRecoveryService recovery;
    private final Duration scanInterval;
    private final ScheduledExecutorService scheduler;

    public ScheduledStaleJobReaperAdapterOut(StaleJobRecoveryService recovery,
                                             AsyncJobsProperties properties) {
        this.recovery = recovery;
        this.scanInterval = properties.recovery().scanInterval();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "async-jobs-reaper");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void afterPropertiesSet() {
        long millis = scanInterval.toMillis();
        scheduler.scheduleWithFixedDelay(this::scan, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Recuperacao de jobs orfaos ativa (intervalo de varredura: {})", scanInterval);
    }

    private void scan() {
        try {
            int acted = recovery.recover();
            if (acted > 0) {
                log.info("Varredura de jobs orfaos agiu sobre {} job(s)", acted);
            }
        } catch (Exception e) {
            log.error("Falha na varredura de jobs orfaos; sera repetida no proximo ciclo", e);
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
