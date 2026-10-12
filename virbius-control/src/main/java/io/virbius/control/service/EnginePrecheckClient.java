package io.virbius.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EnginePrecheckClient {

    private static final Logger log = LoggerFactory.getLogger(EnginePrecheckClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String engineBaseUrl;

    public EnginePrecheckClient(
            @Value("${virbius.engine.base-url:http://127.0.0.1:8082}") String engineBaseUrl) {
        this.engineBaseUrl = engineBaseUrl;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> precheck(String tenantId, String content) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", tenantId);
        payload.put("content", content);

        String url = engineBaseUrl + "/v1/precheck";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            log.warn("qa precheck failed status={} body={}", resp.statusCode(), resp.body());
            throw new IllegalStateException("engine precheck HTTP " + resp.statusCode());
        }
        return mapper.readValue(resp.body(), Map.class);
    }
}
