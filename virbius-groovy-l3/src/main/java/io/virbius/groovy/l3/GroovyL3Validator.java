package io.virbius.groovy.l3;

import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;

/** Gate G6: size + dangerous token + parse check (PoC no full AST sandbox; runtime only exposes ctx via Binding). */
public final class GroovyL3Validator {

    public static final int MAX_BODY_BYTES = 32 * 1024;

    private static final java.util.Set<String> FORBIDDEN_TOKENS =
            java.util.Set.of("Runtime", "ProcessBuilder", "Class.forName", "System.exit", "@Grab", "GroovyShell");

    private GroovyL3Validator() {}

    public static void validate(String scriptBody) throws GroovyL3ValidationException {
        if (scriptBody == null || scriptBody.isBlank()) {
            throw new GroovyL3ValidationException("groovy body is empty");
        }
        if (scriptBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new GroovyL3ValidationException("groovy body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        String lower = scriptBody.toLowerCase();
        for (String token : FORBIDDEN_TOKENS) {
            if (lower.contains(token.toLowerCase())) {
                throw new GroovyL3ValidationException("forbidden token in groovy script: " + token);
            }
        }
        if (!scriptBody.contains("decide")) {
            throw new GroovyL3ValidationException("groovy script must define decide(ctx)");
        }
        try {
            new GroovyShell(new CompilerConfiguration()).parse(scriptBody);
        } catch (Exception e) {
            throw new GroovyL3ValidationException("groovy parse failed: " + e.getMessage());
        }
    }

    static CompilerConfiguration executionConfiguration() {
        return new CompilerConfiguration();
    }
}
