package io.virbius.groovy.l3;

public record GroovyL3Decision(
        String effectiveAction,
        boolean wouldBlock,
        String safeReplyId,
        String enforceMode) {

    public static GroovyL3Decision allow(String enforceMode) {
        return new GroovyL3Decision("allow", false, null, enforceMode);
    }
}
