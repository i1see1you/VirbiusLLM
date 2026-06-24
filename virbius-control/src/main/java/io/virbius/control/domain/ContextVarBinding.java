package io.virbius.control.domain;

import java.util.Locale;
import java.util.Map;

/** Logical variable → HTTP/body source mapping. */
public record ContextVarBinding(String logical, String from, String name, String field) {

    public static final String FROM_QUERY = "query";
    public static final String FROM_HEADER = "header";
    public static final String FROM_SUBJECT = "subject";
    public static final String FROM_NETWORK = "network";

    public ContextVarBinding {
        if (logical == null || logical.isBlank()) {
            throw new IllegalArgumentException("logical name required");
        }
        logical = logical.trim();
        if (!logical.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("logical name must be lowercase snake_case: " + logical);
        }
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("from required for " + logical);
        }
        from = from.trim().toLowerCase(Locale.ROOT);
        switch (from) {
            case FROM_QUERY, FROM_HEADER -> {
                if (name == null || name.isBlank()) {
                    name = logical;
                } else {
                    name = name.trim();
                }
            }
            case FROM_SUBJECT, FROM_NETWORK -> {
                if (field == null || field.isBlank()) {
                    field = logical;
                } else {
                    field = field.trim().toLowerCase(Locale.ROOT);
                }
            }
            default -> throw new IllegalArgumentException("from must be query, header, subject, or network");
        }
    }

    @SuppressWarnings("unchecked")
    public static ContextVarBinding fromMap(String logical, Map<String, Object> def) {
        if (def == null) {
            throw new IllegalArgumentException("binding def required for " + logical);
        }
        String from = def.get("from") != null ? def.get("from").toString() : null;
        String name = def.get("name") != null ? def.get("name").toString() : null;
        String field = def.get("field") != null ? def.get("field").toString() : null;
        return new ContextVarBinding(logical, from, name, field);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("from", from);
        if (name != null && !name.isBlank()) {
            m.put("name", name);
        }
        if (field != null && !field.isBlank()) {
            m.put("field", field);
        }
        return m;
    }
}
