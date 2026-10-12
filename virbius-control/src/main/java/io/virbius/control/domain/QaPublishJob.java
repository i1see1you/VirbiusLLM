package io.virbius.control.domain;

import java.time.Instant;

public record QaPublishJob(
        String publishId,
        String tenantId,
        String qaId,
        String status,
        Long snapshotRevision,
        String errors,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {}
