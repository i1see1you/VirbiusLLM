package io.virbius.control.domain;

import java.time.Instant;
import java.util.List;

public record QaEntry(
        String tenantId,
        String qaId,
        String entryId,
        String question,
        List<String> variants,
        String answer,
        String matchMode,
        List<String> tags,
        int priority,
        boolean enabled,
        Instant expiresAt,
        Instant lastPrecheckAt,
        Boolean lastPrecheckOk,
        Instant updatedAt) {}
