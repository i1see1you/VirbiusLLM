package io.virbius.engine.config;

import io.virbius.policy.CounterStore;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;

@Configuration
public class PolicyRedisConfig {

    @Bean
    public Optional<JedisPool> jedisPool(@Value("${virbius.redis.url:}") String redisUrl) {
        return CounterStore.createPool(redisUrl);
    }
}
