package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.policy.CounterStore;
import io.virbius.policy.CumulativeWindow;
import io.virbius.policy.MatchContext;
import io.virbius.policy.ValueResolver;
import io.virbius.policy.ValueSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

@Component
public class CumulativeRunner {

    private final RuleCache cache;
    private final Optional<CounterStore> counterStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public CumulativeRunner(RuleCache cache, Optional<JedisPool> jedisPool) {
        this.cache = cache;
        this.counterStore = jedisPool.map(CounterStore::new);
    }

    public List<SignalDto> run(String tenantId, MatchContext ctx) {
        List<SignalDto> signals = new ArrayList<>();
        if (counterStore.isEmpty()) {
            return signals;
        }
        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!"cumulative".equals(rule.runtime())) {
                continue;
            }
            JsonNode body = parseBody(rule.body());
            if (body == null) {
                continue;
            }
            String cumulativeName = text(body, "cumulative_name");
            if (cumulativeName == null) {
                continue;
            }
            String dimension = text(body, "dimension");
            if (dimension == null) {
                dimension = "user_id";
            }
            ValueSource vs = ValueSource.fromJson(body.get("value_source"));
            Optional<String> value = ValueResolver.resolve(dimension, vs, ctx);
            if (value.isEmpty()) {
                continue;
            }
            int wMin = body.has("W_minutes") ? body.get("W_minutes").asInt() : 60;
            String windowKind = text(body, "window_kind");
            if (windowKind == null) {
                windowKind = "rolling";
            }
            String timezone = text(body, "timezone");
            ZoneId zone = ZoneId.of(timezone != null ? timezone : "UTC");
            int threshold = body.has("threshold") ? body.get("threshold").asInt() : 100;
            String compareOp = text(body, "compare_op");
            long count = counterStore
                    .get()
                    .read(tenantId, cumulativeName, value.get(), wMin, windowKind, zone);
            if (counterStore.get().exceeded(count, threshold, compareOp)) {
                signals.add(new SignalDto(
                        rule.ruleId(),
                        rule.ruleRevision(),
                        rule.layer(),
                        rule.layer(),
                        rule.riskScore(),
                        rule.reasonCode(),
                        rule.intentAction(),
                        rule.enforceMode(),
                        rule.canaryPercent() > 0 ? rule.canaryPercent() : null));
            }
        }
        return signals;
    }

    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && !n.isNull() ? n.asText() : null;
    }
}
