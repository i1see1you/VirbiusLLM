package io.virbius.groovy.l3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroovyL3ExecutorTest {

    private final GroovyL3Executor executor = new GroovyL3Executor(500);

    @Test
    void dryRunWouldBlock() throws Exception {
        PolicyContext ctx = context("dry_run", signalBlock());
        GroovyL3Decision d = executor.execute(GroovyL3Defaults.DEFAULT_DECIDE_SCRIPT, ctx);
        assertEquals("allow", d.effectiveAction());
        assertTrue(d.wouldBlock());
    }

    @Test
    void fullBlock() throws Exception {
        PolicyContext ctx = context("full", signalBlock());
        GroovyL3Decision d = executor.execute(GroovyL3Defaults.DEFAULT_DECIDE_SCRIPT, ctx);
        assertEquals("block", d.effectiveAction());
        assertFalse(d.wouldBlock());
    }

    @Test
    void rejectsRuntimeExec() {
        assertThrows(
                GroovyL3ValidationException.class,
                () -> GroovyL3Validator.validate("def decide(ctx) { Runtime.getRuntime().exec('x') }"));
    }

    private static PolicyContext context(String enforceMode, L3SignalView signal) {
        L3RuleView l3 = new L3RuleView("cloud_groovy_l3", 1, enforceMode, 100, 100);
        return new PolicyContext(
                "default",
                "sess-1",
                "chat",
                "cloud_groovy_l3",
                Map.of("cloud_groovy_l3", l3),
                List.of(signal));
    }

    private static L3SignalView signalBlock() {
        return new L3SignalView("cloud_prompt_l1", 1, "cloud", 100, "block", "PROMPT_INJECTION");
    }
}
