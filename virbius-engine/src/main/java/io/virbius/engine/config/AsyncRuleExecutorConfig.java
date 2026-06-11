package io.virbius.engine.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncRuleExecutorConfig {

    @Bean("asyncRuleExecutor")
    public ExecutorService asyncRuleExecutor() {
        var counter = new AtomicInteger(0);
        return Executors.newFixedThreadPool(10, r -> {
            Thread t = new Thread(r, "async-rule-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Bean("dryRunRuleExecutor")
    public ExecutorService dryRunRuleExecutor() {
        var counter = new AtomicInteger(0);
        return Executors.newFixedThreadPool(10, r -> {
            Thread t = new Thread(r, "dry-run-rule-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
