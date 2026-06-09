package io.virbius.control.domain;

import java.time.Instant;

public record EdgeArtifactMeta(
        String tenantId, String appId, long artifactRevision, String contentSha256, Instant publishedAt) {}
