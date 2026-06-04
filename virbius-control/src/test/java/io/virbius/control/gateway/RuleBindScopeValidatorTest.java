package io.virbius.control.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleBindScopeValidatorTest {

    private static final Map<String, Object> METADATA = Map.of(
            "gateway",
            Map.of("routes", List.of(Map.of("uri", "/v1/chat/*", "methods", List.of("POST")))));

    @Test
    void acceptsUriCoveredByGatewayWildcard() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1",
                "poc-default",
                "cloud",
                "groovy",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "route", "bind_ref", Map.of("uris", List.of("/v1/chat/completions"))),
                Map.of());
        assertDoesNotThrow(() -> RuleBindScopeValidator.validateRouteUris(req, METADATA));
    }

    @Test
    void rejectsUriNotCoveredByGateway() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1",
                "poc-default",
                "cloud",
                "groovy",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "route", "bind_ref", Map.of("uris", List.of("/v1/embeddings"))),
                Map.of());
        assertThrows(IllegalArgumentException.class, () -> RuleBindScopeValidator.validateRouteUris(req, METADATA));
    }

    @Test
    void skipsWhenOnlyScenes() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1",
                "poc-default",
                "cloud",
                "groovy",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "route", "bind_ref", Map.of("scenes", List.of("beta_chat"))),
                Map.of());
        assertDoesNotThrow(() -> RuleBindScopeValidator.validateRouteUris(req, Map.of()));
    }
}
