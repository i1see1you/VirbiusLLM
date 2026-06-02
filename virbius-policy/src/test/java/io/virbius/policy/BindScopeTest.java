package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BindScopeTest {

    @Test
    void globalAlwaysMatches() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "general_chat", "/v1/chat/completions");
        assertTrue(BindScope.matches("global", java.util.Map.of(), ctx));
    }

    @Test
    void routeUriExactMatch() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "x", "/v1/chat/completions");
        assertTrue(BindScope.matches("route", java.util.Map.of("uris", java.util.List.of("/v1/chat/completions")), ctx));
        assertFalse(BindScope.matches("route", java.util.Map.of("uris", java.util.List.of("/v1/other")), ctx));
    }

    @Test
    void routeUriPrefixMatch() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "x", "/v1/chat/completions");
        assertTrue(BindScope.matches("route", java.util.Map.of("uris", java.util.List.of("/v1/chat/*")), ctx));
    }

    @Test
    void routeUriPriorityOverScene() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "general_chat", "/v1/other");
        assertFalse(BindScope.matches("route", java.util.Map.of("uris", java.util.List.of("/v1/chat/completions"), "scenes", java.util.List.of("general_chat")), ctx));
    }

    @Test
    void routeSceneFallbackWhenNoUris() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "medical_qa", null);
        assertTrue(BindScope.matches("route", java.util.Map.of("scenes", java.util.List.of("medical_qa")), ctx));
    }
}
