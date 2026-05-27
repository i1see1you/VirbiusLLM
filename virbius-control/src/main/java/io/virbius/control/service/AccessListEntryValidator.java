package io.virbius.control.service;

import io.virbius.control.domain.enums.AccessListDimension;
import java.util.Map;
import java.util.Set;

public final class AccessListEntryValidator {

    private AccessListEntryValidator() {}

    public static String normalizeAndValidate(AccessListDimension dimension, String raw, Map<String, Object> bundleMetadata) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("value required");
        }
        String v = raw.trim();
        return switch (dimension) {
            case VAR -> validateVarEntry(v, ContextBindingsHelper.logicalNames(bundleMetadata));
            default -> v;
        };
    }

    public static String normalizeAndValidate(AccessListDimension dimension, String raw) {
        return normalizeAndValidate(dimension, raw, Map.of());
    }

    private static String validateVarEntry(String v, Set<String> declared) {
        int eq = v.indexOf('=');
        if (eq <= 0 || eq >= v.length() - 1) {
            throw new IllegalArgumentException("var entry must be logical_name=value, e.g. app_id=beta");
        }
        String logical = v.substring(0, eq).trim();
        String value = v.substring(eq + 1).trim();
        if (logical.isEmpty() || value.isEmpty()) {
            throw new IllegalArgumentException("var entry must be logical_name=value");
        }
        if (!logical.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid logical name: " + logical);
        }
        if (!declared.isEmpty() && !declared.contains(logical)) {
            throw new IllegalArgumentException("logical name not in context_bindings: " + logical);
        }
        return logical + "=" + value;
    }
}
