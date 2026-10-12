package com.brianmehrman.harness.api;

import com.brianmehrman.harness.runs.CommandDispatcher;
import com.brianmehrman.harness.runs.WorkflowGateway;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** Delivers accepted outbox commands independently of the HTTP request lifecycle. */
@Component
@Profile("api")
public class ApiCommandPump {
    private static final Logger LOG = Logger.getLogger(ApiCommandPump.class.getName());
    private final CommandDispatcher dispatcher;
    private final boolean enabled;

    public ApiCommandPump(CommandDispatcher dispatcher,
            @Value("${harness.api.dispatch-enabled:true}") boolean enabled) {
        this.dispatcher = dispatcher;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${harness.api.dispatch-interval-ms:1000}")
    public void drain() {
        if (!enabled) return;
        int failedClaims = 0;
        for (int count = 0; count < 20; count++) {
            try { if (!dispatcher.dispatchOnce()) return; }
            catch (CommandDispatcher.ClaimedDeliveryException error) {
                LOG.log(Level.WARNING, "Claimed run command delivery failed: {0}",
                        error.getCause().getClass().getSimpleName());
                if (++failedClaims >= 3) return;
            }
            catch (RuntimeException error) {
                LOG.log(Level.WARNING, "Run command delivery attempt failed: {0}", error.getClass().getSimpleName());
                // Stop this tick: a failure before a row is claimed has no durable backoff.
                return;
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("api")
    static class DispatcherConfiguration {
        @Bean CommandDispatcher commandDispatcher(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                WorkflowGateway gateway) {
            return new CommandDispatcher(jdbc, transactions, gateway);
        }
    }
}
