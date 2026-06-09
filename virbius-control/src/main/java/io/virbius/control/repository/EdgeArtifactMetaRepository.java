package io.virbius.control.repository;

import io.virbius.control.domain.EdgeArtifactMeta;
import java.util.Optional;

public interface EdgeArtifactMetaRepository {

    Optional<EdgeArtifactMeta> get(String tenantId, String appId);

    void save(EdgeArtifactMeta meta);
}
