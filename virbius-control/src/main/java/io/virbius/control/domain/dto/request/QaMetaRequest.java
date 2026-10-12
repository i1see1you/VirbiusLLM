package io.virbius.control.domain.dto.request;

public record QaMetaRequest(
        String name,
        String description,
        String rollout_state,
        Integer canary_percent) {}
