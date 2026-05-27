package io.virbius.control.domain.enums;

public enum AccessListPolarity {
    DENY("deny"),
    ALLOW("allow");

    private final String value;

    AccessListPolarity(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static AccessListPolarity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("polarity required");
        }
        String n = raw.trim().toLowerCase();
        for (AccessListPolarity p : values()) {
            if (p.value.equals(n)) {
                return p;
            }
        }
        throw new IllegalArgumentException("polarity must be deny or allow");
    }
}