package io.virbius.control.service;

import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.EdgeArtifactMeta;
import io.virbius.control.repository.EdgeArtifactMetaRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EdgeDeliveryService {

    private final Path dataDir;
    private final EdgeArtifactMetaRepository metaRepository;

    public EdgeDeliveryService(
            @Value("${virbius.data-dir:./data}") String dataDir, EdgeArtifactMetaRepository metaRepository) {
        this.dataDir = Path.of(dataDir);
        this.metaRepository = metaRepository;
    }

    public Map<String, Object> policyVersion(String tenantId, String appId) {
        EdgeArtifactMeta meta = requireMeta(tenantId, appId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        out.put("app_id", appId);
        out.put("artifact_revision", meta.artifactRevision());
        out.put("content_sha256", meta.contentSha256());
        out.put("published_at", meta.publishedAt().toString());
        return out;
    }

    public EdgeArtifactMeta requireMeta(String tenantId, String appId) {
        return metaRepository
                .get(tenantId, appId)
                .orElseThrow(() -> new ResourceNotFoundException("edge artifact", tenantId + "/" + appId));
    }

    public byte[] readManifestBytes(String tenantId, String appId) {
        Path file = manifestPath(tenantId, appId);
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("edge manifest file", file.toString());
        }
        try {
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read edge manifest: " + e.getMessage(), e);
        }
    }

    public Path manifestPath(String tenantId, String appId) {
        return dataDir.resolve("edge").resolve(tenantId).resolve(appId).resolve("edge-manifest.json");
    }
}
