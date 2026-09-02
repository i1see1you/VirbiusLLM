package io.virbius.engine.eval;

import java.util.Map;

/** Category -> rule mapping lookups tolerant of Spring relaxed-binding key mangling. */
public final class PromptCategoryUtil {

    private PromptCategoryUtil() {}

    /**
     * Spring Boot relaxed binding canonicalizes @ConfigurationProperties map keys by dropping
     * characters that are not allowed in a property name (e.g. spaces, {@code &}). The same
     * normalization is applied here so that verbatim model category labels such as
     * {@code "Sexual Content or Sexual Acts"} still match the bound keys.
     */
    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.replaceAll("[^A-Za-z0-9-]", "");
    }

    /** @return the rule id mapped to {@code category}, or null if unmapped. */
    public static String ruleIdFor(Map<String, String> mapping, String category) {
        if (mapping == null || mapping.isEmpty() || category == null) {
            return null;
        }
        String canonical = canonical(category);
        if (canonical == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (canonical.equals(canonical(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }
}
