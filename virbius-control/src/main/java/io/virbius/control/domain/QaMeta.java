package io.virbius.control.domain;

import java.time.Instant;

public record QaMeta(
        String tenantId,
        String qaId,
        String name,
        String description,
        String status,
        String rolloutState,
        int canaryPercent,
        long revision,
        Instant createdAt,
        Instant updatedAt) {}
