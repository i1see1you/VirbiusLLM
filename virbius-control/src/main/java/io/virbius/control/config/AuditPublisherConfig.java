package io.virbius.control.config;

import io.virbius.policy.audit.AuditEventPublisher;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditPublisherConfig {

    private AuditEventPublisher publisher;

    @Bean
    public AuditEventPublisher auditEventPublisher(
            ControlJedisPools jedisPools,
            @Value("${audit.publish.backend:redis-stream}") String backend,
            @Value("${audit.publish.redis.stream-key:virbius:audit:events}") String streamKey,
            @Value("${audit.publish.async:true}") boolean async,
            @Value("${audit.publish.queue-max:2000}") int queueMax,
            @Value("${audit.publish.flush-batch-size:64}") int batchSize,
            @Value("${audit.publish.flush-interval-ms:100}") long flushIntervalMs) {
        publisher = new AuditEventPublisher(
                jedisPools.pool(), backend, streamKey, async, queueMax, batchSize, flushIntervalMs);
        return publisher;
    }

    @PreDestroy
    void shutdownPublisher() {
        if (publisher != null) {
            publisher.close();
        }
    }
}
