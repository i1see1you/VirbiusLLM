package io.virbius.policy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Gateway rule binding: global / service / route. See docs/openspec/bind-scope.md */
public final class BindScope {

    public static final String GLOBAL = "global";
    public static final String SERVICE = "service";
    public static final String ROUTE = "route";

    private BindScope() {}

    public static String scopeFromRuleScope(Map<String, Object> scope) {
        if (scope == null || scope.isEmpty()) {
            return GLOBAL;
        }
        Object raw = scope.get("bind_scope");
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim().toLowerCase(Locale.ROOT);
        }
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> bindRefFromScope(Map<String, Object> scope) {
        if (scope == null) {
            return Map.of();
        }
        Object ref = scope.get("bind_ref");
        if (ref instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            return out;
        }
        Map<String, Object> legacy = new LinkedHashMap<>();
        Object scenes = scope.get("scenes");
        if (scenes instanceof List<?> list && !list.isEmpty()) {
            legacy.put("scenes", list);
        }
        return legacy;
    }

    public static boolean matches(JsonNode body, MatchContext ctx) {
        if (body == null) {
            return true;
        }
        String scope = text(body, "bind_scope");
        if (scope == null || scope.isBlank()) {
            scope = GLOBAL;
        }
        JsonNode ref = body.get("bind_ref");
        return matches(scope, ref, ctx);
    }

    public static boolean matches(String bindScope, Map<String, Object> bindRef, MatchContext ctx) {
        JsonNode refNode = bindRef == null || bindRef.isEmpty() ? null : JsonMapperHelper.toJsonNode(bindRef);
        return matches(bindScope, refNode, ctx);
    }

    public static boolean matches(String bindScope, JsonNode bindRef, MatchContext ctx) {
        String scope = bindScope != null ? bindScope.trim().toLowerCase(Locale.ROOT) : GLOBAL;
        return switch (scope) {
            case SERVICE -> matchesService(bindRef, ctx);
            case ROUTE -> matchesRoute(bindRef, ctx);
            default -> true;
        };
    }

    private static boolean matchesService(JsonNode ref, MatchContext ctx) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        boolean any = false;
        any |= fieldMatches(ref, "upstream_id", ctx.upstreamId());
        any |= fieldMatches(ref, "consumer_id", ctx.consumerId());
        any |= fieldMatches(ref, "api_key_group", ctx.apiKeyGroup());
        return any;
    }

    private static boolean fieldMatches(JsonNode ref, String field, String actual) {
        if (actual == null || actual.isBlank()) {
            return false;
        }
        JsonNode expected = ref.get(field);
        if (expected == null || expected.isNull()) {
            return false;
        }
        return actual.equals(expected.asText());
    }

    private static boolean matchesRoute(JsonNode ref, MatchContext ctx) {
        if (ref == null) {
            return false;
        }
        List<String> uris = stringList(ref.get("uris"));
        if (!uris.isEmpty()) {
            String routeUri = normalizeUri(ctx.routeUri());
            if (routeUri == null) {
                return false;
            }
            for (String pattern : uris) {
                if (uriMatches(routeUri, pattern)) {
                    return true;
                }
            }
            return false;
        }
        List<String> scenes = stringList(ref.get("scenes"));
        if (scenes.isEmpty()) {
            return false;
        }
        String scene = ctx.scene();
        if (scene == null || scene.isBlank()) {
            return false;
        }
        for (String s : scenes) {
            if ("*".equals(s) || scene.equals(s)) {
                return true;
            }
        }
        return false;
    }

    public static boolean uriMatches(String routeUri, String pattern) {
        if (routeUri == null || pattern == null || pattern.isBlank()) {
            return false;
        }
        String uri = normalizeUri(routeUri);
        String pat = normalizeUri(pattern);
        if (uri == null || pat == null) {
            return false;
        }
        if (pat.endsWith("*")) {
            String prefix = pat.substring(0, pat.length() - 1);
            return uri.startsWith(prefix);
        }
        return uri.equals(pat);
    }

    public static String normalizeUri(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        return s;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && !n.isNull() ? n.asText() : null;
    }

    /** Minimal map→JsonNode without pulling ObjectMapper into hot path callers. */
    static final class JsonMapperHelper {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        static JsonNode toJsonNode(Map<String, Object> map) {
            return MAPPER.valueToTree(map);
        }
    }
}
