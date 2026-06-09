package io.virbius.control.domain;

import java.time.Instant;

public record EdgeTenantCredential(
        String credentialId,
        String tenantId,
        String keyHash,
        String keyPrefix,
        String status,
        Instant createdAt,
        Instant revokedAt,
        Instant lastUsedAt) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
