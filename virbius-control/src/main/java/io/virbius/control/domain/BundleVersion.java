package io.virbius.control.domain;

import java.util.Map;

public record BundleVersion(
        String tenantId,
        String bundleId,
        String version,
        String status,
        String publishId,
        Map<String, Object> syncAck,
        Map<String, Object> metadata) {}
