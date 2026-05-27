package io.virbius.engine.eval;

public record EngineDecisionDto(
        String effectiveAction,
        boolean wouldBlock,
        String safeReplyId,
        String enforceMode) {}
