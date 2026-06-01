package io.virbius.control.domain;

public record CumulativeDef(
        String tenantId,
        String cumulativeName,
        String description,
        String dimension,
        String windowKind,
        Integer windowMinutes,
        Integer windowHours,
        String timezone,
        int threshold,
        String compareOp,
        String onExceedSuggest,
        int onExceedRiskScore,
        String onExceedReasonCode,
        int priority,
        String status) {}
