package io.virbius.control.config;

import io.virbius.policy.CounterStore;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;

@Configuration
public class ControlRedisConfig {

    @Bean
    public Optional<JedisPool> controlJedisPool(@Value("${virbius.redis.url:}") String redisUrl) {
        return CounterStore.createPool(redisUrl);
    }
}
