package io.virbius.control.domain.dto.request;

import java.util.List;

public record QaEntryInput(
        String entry_id,
        String question,
        List<String> variants,
        String answer,
        String match_mode,
        List<String> tags,
        Integer priority,
        Boolean enabled,
        String expires_at) {}
