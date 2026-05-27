package io.virbius.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/** Emits APISIX route JSON from Bundle metadata {@code gateway.routes}. */
final class GatewayApisixEmitter {

    private GatewayApisixEmitter() {}

    static int emitRoutes(JsonNode root, Path gatewayDir, ObjectMapper json) throws IOException {
        JsonNode gateway = root.get("gateway");
        if (gateway == null || gateway.isMissingNode()) {
            gateway = root.path("bundle").path("gateway");
        }
        JsonNode routes = gateway.path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            return 0;
        }
        Files.createDirectories(gatewayDir);
        String tenantId = root.path("tenant_id").asText("default");
        String bundleVersion = root.path("version").asText("0.1.0");
        boolean evaluateDefault = gateway.path("evaluate").asBoolean(true);
        String serviceId = "virbius-svc-" + tenantId;

        int count = 0;
        for (JsonNode route : routes) {
            String scene = route.path("scene").asText("");
            if (scene.isBlank()) {
                continue;
            }
            ObjectNode doc = json.createObjectNode();
            doc.put("id", "virbius-route-" + scene.replace('_', '-'));
            doc.put("name", scene + "-completions");
            doc.put(
                    "desc",
                    "generated from bundle gateway.routes scene=" + scene);
            doc.put("uri", route.path("uri").asText("/v1/chat/completions"));
            if (route.has("priority")) {
                doc.put("priority", route.path("priority").asInt());
            }
            doc.put("service_id", serviceId);
            ArrayNode methods = doc.putArray("methods");
            JsonNode methodsNode = route.get("methods");
            if (methodsNode != null && methodsNode.isArray() && !methodsNode.isEmpty()) {
                methodsNode.forEach(m -> methods.add(m.asText()));
            } else {
                methods.add("POST");
            }
            appendMatchVars(route, doc);
            ObjectNode plugins = doc.putObject("plugins");
            plugins.set("serverless-pre-function", sceneHeaderInjector(json, scene));
            ObjectNode guard = plugins.putObject("virbius-guard");
            guard.put("scene", scene);
            guard.put("evaluate", route.has("evaluate") ? route.path("evaluate").asBoolean() : evaluateDefault);
            guard.put("bundle_version", bundleVersion);
            guard.put("tenant_id", tenantId);

            Path out = gatewayDir.resolve("apisix-routes-" + scene + ".json");
            json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), doc);
            count++;
        }
        return count;
    }

    private static void appendMatchVars(JsonNode route, ObjectNode doc) {
        JsonNode headers = route.path("match").path("headers");
        if (!headers.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
        if (!fields.hasNext()) {
            return;
        }
        Map.Entry<String, JsonNode> first = fields.next();
        String headerName = "http_" + first.getKey().toLowerCase().replace('-', '_');
        String value = first.getValue().isTextual() ? first.getValue().asText() : first.getValue().asText("");
        ArrayNode vars = doc.putArray("vars");
        ArrayNode clause = vars.addArray();
        clause.add(headerName);
        clause.add("==");
        clause.add(value);
    }

    private static ObjectNode sceneHeaderInjector(ObjectMapper json, String scene) {
        ObjectNode plugin = json.createObjectNode();
        plugin.put("phase", "rewrite");
        ArrayNode functions = plugin.putArray("functions");
        functions.add(
                "return function(conf, ctx)\n  ngx.req.set_header('X-Virbius-Scene', '"
                        + scene
                        + "')\nend");
        return plugin;
    }

    static void emitService(JsonNode root, Path gatewayDir, ObjectMapper json) throws IOException {
        JsonNode gateway = root.get("gateway");
        if (gateway == null || gateway.isMissingNode()) {
            gateway = root.path("bundle").path("gateway");
        }
        JsonNode routes = gateway.path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            return;
        }
        String tenantId = root.path("tenant_id").asText("default");
        String bundleVersion = root.path("version").asText("0.1.0");
        Files.createDirectories(gatewayDir);

        ObjectNode doc = json.createObjectNode();
        doc.put("id", "virbius-svc-" + tenantId);
        doc.put("name", "virbius-" + tenantId + "-llm");
        doc.put("desc", "generated from bundle metadata tenant=" + tenantId);

        ObjectNode upstream = doc.putObject("upstream");
        upstream.put("type", "roundrobin");
        ObjectNode nodes = upstream.putObject("nodes");
        nodes.put("mock-llm.internal:443", 1);

        ObjectNode plugins = doc.putObject("plugins");
        ObjectNode guard = plugins.putObject("virbius-guard");
        guard.put("bundle_version", bundleVersion);
        guard.put("evaluate", gateway.path("evaluate").asBoolean(true));
        guard.put("tenant_id", tenantId);
        if (gateway.has("fail_mode")) {
            guard.put("fail_mode", gateway.path("fail_mode").asText());
        }
        JsonNode cloudScan = gateway.path("cloud_scan");
        if (cloudScan.has("agent_url")) {
            guard.put("agent_url", cloudScan.path("agent_url").asText());
        }
        guard.put(
                "lists_file",
                "/usr/local/apisix/conf/virbius/data/gateway/" + tenantId + "-access-lists.json");

        JsonNode routesNode = gateway.path("routes");
        if (routesNode.isArray() && !routesNode.isEmpty()) {
            guard.put("scene", routesNode.get(0).path("scene").asText("general_chat"));
        }

        Path out = gatewayDir.resolve("apisix-service-" + tenantId + ".json");
        json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), doc);
    }
}
