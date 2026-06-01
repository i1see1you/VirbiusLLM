package io.virbius.engine.config;

import io.virbius.policy.audit.AuditEventPublisher;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;

@Configuration
public class AuditPublisherConfig {

    @Bean
    public AuditEventPublisher auditEventPublisher(
            Optional<JedisPool> jedisPool,
            @Value("${audit.publish.backend:redis-stream}") String backend,
            @Value("${audit.publish.redis.stream-key:virbius:audit:events}") String streamKey) {
        return new AuditEventPublisher(jedisPool, backend, streamKey);
    }
}
