package io.virbius.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EdgeManifestEmitterTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void emitsEdgeRulesAndSdkConfig() throws Exception {
        Path bundle =
                Path.of("examples/poc-default-bundle.yaml").toAbsolutePath().normalize();
        if (!Files.exists(bundle)) {
            bundle = Path.of("../examples/poc-default-bundle.yaml").toAbsolutePath().normalize();
        }
        JsonNode root = yaml.readTree(Files.readString(bundle));
        Map<String, Object> manifest = EdgeManifestEmitter.buildManifest(root);

        assertEquals("1", manifest.get("manifest_version"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) manifest.get("rules");
        assertEquals(1, rules.size());
        assertEquals("edge_l0_content_deny", rules.get(0).get("rule_id"));
        assertEquals("dry_run", rules.get(0).get("rollout_state"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sdk = (Map<String, Object>) manifest.get("sdk_config");
        assertEquals(0.1, ((Number) sdk.get("audit_sample_rate_allow")).doubleValue(), 0.001);

        EdgeManifestEmitter.write(tempDir, root, json);
        assertTrue(Files.exists(tempDir.resolve("edge-manifest.json")));
    }
}
