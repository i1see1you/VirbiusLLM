package io.virbius.groovy.l3;

import io.virbius.policy.ListMatcher;
import io.virbius.policy.MatchContext;
import io.virbius.policy.ValueResolver;
import io.virbius.policy.ValueSource;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * List / cumulative helpers for {@code decide(ctx)} scripts.
 * Injected into {@link PolicyContext} at evaluation time.
 */
public final class ScriptEnvironment {

    private final String tenantId;
    private final MatchContext matchCtx;
    private final Map<String, ListDefinition> lists;
    private final Map<String, CumulativeDefinition> cumulatives;
    private final CumulativeReader cumulativeReader;

    public ScriptEnvironment(
            String tenantId,
            MatchContext matchCtx,
            Map<String, ListDefinition> lists,
            Map<String, CumulativeDefinition> cumulatives,
            CumulativeReader cumulativeReader) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.matchCtx = matchCtx;
        this.lists = lists != null ? Map.copyOf(lists) : Map.of();
        this.cumulatives = cumulatives != null ? Map.copyOf(cumulatives) : Map.of();
        this.cumulativeReader = cumulativeReader;
    }

    public boolean listMatch(String listName) {
        if (listName == null || listName.isBlank()) {
            return false;
        }
        ListDefinition def = lists.get(listName.trim());
        if (def == null || def.entries().isEmpty()) {
            return false;
        }
        Optional<String> value = ValueResolver.resolve(def.dimension(), def.valueSource(), matchCtx);
        if (value.isEmpty()) {
            return false;
        }
        return listMatch(listName, value.get());
    }

    public boolean listMatch(String listName, String value) {
        if (listName == null || listName.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        ListDefinition def = lists.get(listName.trim());
        if (def == null || def.entries().isEmpty()) {
            return false;
        }
        String content = matchCtx.content() != null ? matchCtx.content() : "";
        return ListMatcher.match(def.dimension(), value, content, def.entries());
    }

    public CumulativeView getCumulative(String cumulativeName) {
        if (cumulativeName == null || cumulativeName.isBlank() || cumulativeReader == null) {
            return new CumulativeView(0, "");
        }
        CumulativeDefinition def = cumulatives.get(cumulativeName.trim());
        if (def == null) {
            return new CumulativeView(0, "");
        }
        Optional<String> value = ValueResolver.resolve(def.dimension(), def.valueSource(), matchCtx);
        if (value.isEmpty()) {
            return new CumulativeView(0, "");
        }
        ZoneId zone = ZoneId.of(def.timezone() != null ? def.timezone() : "UTC");
        long count = cumulativeReader.read(
                tenantId,
                def.cumulativeName(),
                value.get(),
                def.windowMinutes(),
                def.windowKind(),
                zone);
        return new CumulativeView(count, value.get());
    }

    public record ListDefinition(String listName, String dimension, List<String> entries, ValueSource valueSource) {}

    public record CumulativeDefinition(
            String cumulativeName,
            String dimension,
            int windowMinutes,
            String windowKind,
            String timezone,
            ValueSource valueSource) {}

    @FunctionalInterface
    public interface CumulativeReader {
        long read(
                String tenantId,
                String cumulativeName,
                String value,
                int windowMinutes,
                String windowKind,
                ZoneId zone);
    }
}
