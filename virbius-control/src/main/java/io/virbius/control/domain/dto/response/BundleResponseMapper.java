package io.virbius.control.domain.dto.response;

import io.virbius.control.domain.BundleVersion;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BundleResponseMapper {

    private BundleResponseMapper() {}

    public static Map<String, Object> toSummary(BundleVersion b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bundle_id", b.bundleId());
        m.put("tenant_id", b.tenantId());
        m.put("latest_version", b.version());
        m.put("status", b.status());
        return m;
    }

    public static Map<String, Object> toDetail(BundleVersion b) {
        Map<String, Object> m = new LinkedHashMap<>(toSummary(b));
        m.put("version", b.version());
        m.put("metadata", b.metadata() != null ? b.metadata() : Map.of());
        return m;
    }
}