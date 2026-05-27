package io.virbius.control.domain.enums;

public enum AccessListDimension {
    KEYWORD("keyword", true, true, true),
    USER_ID("user_id", false, true, false),
    DEVICE_ID("device_id", false, true, false),
    IP_CIDR("ip_cidr", false, true, false),
    VAR("var", false, true, true);

    private final String value;
    private final boolean deployEdge;
    private final boolean deployGateway;
    private final boolean deployCloud;

    AccessListDimension(String value, boolean deployEdge, boolean deployGateway, boolean deployCloud) {
        this.value = value;
        this.deployEdge = deployEdge;
        this.deployGateway = deployGateway;
        this.deployCloud = deployCloud;
    }

    public String value() {
        return value;
    }

    public boolean deployEdge() {
        return deployEdge;
    }

    public boolean deployGateway() {
        return deployGateway;
    }

    public boolean deployCloud() {
        return deployCloud;
    }

    public static AccessListDimension parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("dimension required");
        }
        String n = raw.trim().toLowerCase();
        for (AccessListDimension d : values()) {
            if (d.value.equals(n)) {
                return d;
            }
        }
        throw new IllegalArgumentException("dimension must be keyword, user_id, device_id, ip_cidr, or var");
    }
}
