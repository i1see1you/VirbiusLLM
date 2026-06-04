package io.virbius.control.service;

import io.virbius.control.domain.AccessListMetaDimension;
import io.virbius.control.domain.enums.AccessListDimension;
import java.util.Map;

public final class AccessListEntryValidator {

    private AccessListEntryValidator() {}

    public static String normalizeAndValidate(String dimension, String raw, Map<String, Object> bundleMetadata) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("value required");
        }
        String v = raw.trim();
        if (v.contains("=")) {
            throw new IllegalArgumentException(
                    "list entry must be a plain value; use script listMatch(name, ctx.var('logical'))");
        }
        String dim = AccessListMetaDimension.validate(dimension);
        if (AccessListMetaDimension.isVar(dim)) {
            return v;
        }
        return normalizeAndValidate(AccessListDimension.parse(dim), raw, bundleMetadata);
    }

    public static String normalizeAndValidate(AccessListDimension dimension, String raw, Map<String, Object> bundleMetadata) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("value required");
        }
        String v = raw.trim();
        if (v.contains("=")) {
            throw new IllegalArgumentException("list entry must be a plain value; use script listMatch(name, ctx.var('logical'))");
        }
        return switch (dimension) {
            case VAR -> throw new IllegalArgumentException("dimension=var is deprecated; use var:logical on list meta");
            default -> v;
        };
    }

    public static String normalizeAndValidate(AccessListDimension dimension, String raw) {
        return normalizeAndValidate(dimension, raw, Map.of());
    }
}
