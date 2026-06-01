package io.virbius.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Emits edge SDK manifest ({@code rules[]}, {@code sdk_config}, legacy {@code lists}). */
public final class EdgeManifestEmitter {

    private EdgeManifestEmitter() {}

    public static Map<String, Object> buildManifest(JsonNode root) {
        String tenantId = root.path("tenant_id").asText("default");
        List<Map<String, Object>> rules = collectEdgeRules(root);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("tenant_id", tenantId);
        manifest.put("manifest_version", "1");
        manifest.put("bundle_id", root.path("bundle_id").asText("poc-default"));
        manifest.put("bundle_version", root.path("version").asText("0.1.0"));
        manifest.put("rules", rules);
        manifest.put("sdk_config", defaultSdkConfig(root));
        manifest.put("lists", legacyLists(rules, tenantId));
        return manifest;
    }

    public static void write(Path outputDir, JsonNode root, ObjectMapper json) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("edge-manifest.json");
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), buildManifest(root));
    }

    public static List<Map<String, Object>> collectEdgeRules(JsonNode root) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        JsonNode rules = root.get("rules");
        if (rules == null || !rules.isArray()) {
            return blocks;
        }
        for (JsonNode rule : rules) {
            if (!"edge".equalsIgnoreCase(rule.path("layer").asText(""))) {
                continue;
            }
            String rolloutState = rule.path("rollout_state").asText("dry_run");
            if (!inExecutionPlane(rolloutState)) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("rule_id", rule.path("rule_id").asText());
            block.put("rule_revision", rule.path("rule_revision").asInt(1));
            block.put("reason_code", rule.path("reason_code").asText(""));
            block.put("risk_score", rule.path("risk_score").asInt(100));
            block.put(
                    "intent_action",
                    rule.path("intent_action").asText("deny"));
            block.put("enforce_mode", enforceMode(rolloutState));
            block.put("rollout_state", rolloutState);
            if ("canary".equalsIgnoreCase(rolloutState) && rule.has("canary_percent")) {
                block.put("canary_percent", rule.get("canary_percent").asInt());
            }
            if (rule.has("body")) {
                JsonNode body = rule.get("body");
                block.put("body", body.isTextual() ? body.asText() : jsonToMap(body));
            }
            blocks.add(block);
        }
        blocks.sort((a, b) -> String.valueOf(a.get("rule_id")).compareTo(String.valueOf(b.get("rule_id"))));
        return blocks;
    }

    private static Map<String, Object> defaultSdkConfig(JsonNode root) {
        Map<String, Object> sdk = new LinkedHashMap<>();
        JsonNode cfg = root.get("sdk_config");
        if (cfg != null && cfg.isObject()) {
            cfg.fields()
                    .forEachRemaining(e -> sdk.put(e.getKey(), jsonToMap(e.getValue())));
        }
        sdk.putIfAbsent("audit_sample_rate_allow", 0.1);
        sdk.putIfAbsent("audit_sample_rate_hit", 1.0);
        sdk.putIfAbsent("audit_flush_interval_ms", 30000);
        sdk.putIfAbsent("audit_queue_max", 500);
        sdk.putIfAbsent("canary_session_key", "device_id");
        return sdk;
    }

    private static Map<String, Object> legacyLists(List<Map<String, Object>> rules, String tenantId) {
        Map<String, Object> lists = new LinkedHashMap<>();
        lists.put("tenant_id", tenantId);
        lists.put("deny", Map.of("keywords", keywordsForListType(rules, "deny")));
        lists.put("allow", Map.of("keywords", keywordsForListType(rules, "allow")));
        return lists;
    }

    @SuppressWarnings("unchecked")
    private static List<String> keywordsForListType(List<Map<String, Object>> rules, String listType) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            Object bodyObj = rule.get("body");
            if (!(bodyObj instanceof Map<?, ?> body)) {
                continue;
            }
            if (!listType.equals(String.valueOf(body.get("list_type")))) {
                continue;
            }
            Object keywords = body.get("keywords");
            if (keywords instanceof List<?> list) {
                for (Object kw : list) {
                    if (kw != null) {
                        out.add(kw.toString());
                    }
                }
            }
        }
        return out;
    }

    private static boolean inExecutionPlane(String rolloutState) {
        if (rolloutState == null || rolloutState.isBlank()) {
            return false;
        }
        return switch (rolloutState.toLowerCase()) {
            case "dry_run", "canary", "full" -> true;
            default -> false;
        };
    }

    private static String enforceMode(String rolloutState) {
        return switch (rolloutState.toLowerCase()) {
            case "canary" -> "canary";
            case "full" -> "full";
            default -> "dry_run";
        };
    }

    private static Object jsonToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            if (node.isInt()) {
                return node.asInt();
            }
            if (node.isDouble() || node.isFloat()) {
                return node.asDouble();
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            return node.asText();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(n -> list.add(jsonToMap(n)));
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), jsonToMap(e.getValue())));
        return map;
    }

    /** Writes legacy {@code {tenant}-content-lists.json} beside manifest. */
    public static void writeLegacyLists(Path outputDir, JsonNode root, ObjectMapper json) throws IOException {
        Map<String, Object> lists = (Map<String, Object>) buildManifest(root).get("lists");
        Files.createDirectories(outputDir);
        String tenantId = root.path("tenant_id").asText("default");
        Path file = outputDir.resolve(tenantId + "-content-lists.json");
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), lists);
    }
}
