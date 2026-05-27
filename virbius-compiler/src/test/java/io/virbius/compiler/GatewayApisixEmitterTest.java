package io.virbius.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayApisixEmitterTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void emitsRoutesFromBundleGatewayMetadata() throws Exception {
        Path bundle =
                Path.of("examples/poc-default-bundle.yaml").toAbsolutePath().normalize();
        if (!Files.exists(bundle)) {
            bundle = Path.of("../examples/poc-default-bundle.yaml").toAbsolutePath().normalize();
        }
        JsonNode root = yaml.readTree(Files.readString(bundle));
        Path gw = tempDir.resolve("gateway");
        int count = GatewayApisixEmitter.emitRoutes(root, gw, json);
        assertEquals(2, count);
        JsonNode general =
                json.readTree(Files.readString(gw.resolve("apisix-routes-general_chat.json")));
        assertEquals("/v1/chat/completions", general.path("uri").asText());
        assertEquals("POST", general.path("methods").get(0).asText());
        assertEquals("general_chat", general.path("plugins").path("virbius-guard").path("scene").asText());
        JsonNode medical =
                json.readTree(Files.readString(gw.resolve("apisix-routes-medical_qa.json")));
        assertEquals(10, medical.path("priority").asInt());
        assertTrue(medical.path("vars").isArray());
    }
}
