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
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "medical-prod_clinical", null);
        assertTrue(BindScope.matches("route", java.util.Map.of("scenes", java.util.List.of("medical-prod_clinical")), ctx));
    }

    @Test
    void serviceMatchesAppIds() {
        MatchContext ctx = MatchContext.withBind(
                "hi", "u1", null, null, null, java.util.Map.of("app_id", "medical-prod"), "x", "/v1/chat/completions");
        assertTrue(BindScope.matches(
                "service", java.util.Map.of("app_ids", java.util.List.of("beta", "medical-prod")), ctx));
        assertFalse(BindScope.matches("service", java.util.Map.of("app_ids", java.util.List.of("beta")), ctx));
    }

    @Test
    void patternCoversPrefixAndExact() {
        assertTrue(BindScope.patternCovers("/v1/chat/*", "/v1/chat/completions"));
        assertTrue(BindScope.patternCovers("/v1/chat/*", "/v1/chat/*"));
        assertTrue(BindScope.patternCovers("/v1/*", "/v1/chat/*"));
        assertFalse(BindScope.patternCovers("/v1/chat/completions", "/v1/chat/*"));
        assertFalse(BindScope.patternCovers("/v1/chat/*", "/v1/*"));
    }

    @Test
    void coveredByAnyGatewayList() {
        assertTrue(BindScope.coveredByAny(
                "/v1/chat/completions", java.util.List.of("/v1/other", "/v1/chat/*")));
        assertFalse(BindScope.coveredByAny("/v1/embeddings", java.util.List.of("/v1/chat/*")));
    }
}
