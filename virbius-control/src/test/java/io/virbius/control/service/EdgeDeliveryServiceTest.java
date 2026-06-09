package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.EdgeArtifactMeta;
import io.virbius.control.repository.EdgeArtifactMetaRepository;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EdgeDeliveryServiceTest {

    @Mock
    private EdgeArtifactMetaRepository metaRepository;

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void policyVersionReturnsMeta() {
        EdgeDeliveryService service = new EdgeDeliveryService(tempDir.toString(), metaRepository);
        Instant published = Instant.parse("2026-05-20T10:00:00Z");
        when(metaRepository.get("default", "demo-app"))
                .thenReturn(Optional.of(new EdgeArtifactMeta("default", "demo-app", 2L, "abc123", published)));

        var out = service.policyVersion("default", "demo-app");
        assertEquals(2L, out.get("artifact_revision"));
        assertEquals("abc123", out.get("content_sha256"));
    }

    @Test
    void readManifestBytesFromDisk() throws Exception {
        EdgeDeliveryService service = new EdgeDeliveryService(tempDir.toString(), metaRepository);
        java.nio.file.Path manifest =
                tempDir.resolve("edge").resolve("default").resolve("demo-app");
        Files.createDirectories(manifest);
        byte[] body = "{\"tenant_id\":\"default\"}".getBytes();
        Files.write(manifest.resolve("edge-manifest.json"), body);

        assertArrayEquals(body, service.readManifestBytes("default", "demo-app"));
    }

    @Test
    void missingMetaThrowsNotFound() {
        EdgeDeliveryService service = new EdgeDeliveryService(tempDir.toString(), metaRepository);
        when(metaRepository.get("default", "missing")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.policyVersion("default", "missing"));
    }
}
