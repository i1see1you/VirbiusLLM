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
                Map.of(),
                null,
                null);
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
                Map.of(),
                null,
                null);
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
                Map.of(),
                null,
                null);
        assertDoesNotThrow(() -> RuleBindScopeValidator.validateRouteUris(req, Map.of()));
    }

    private static final Map<String, Object> SCENE_METADATA = Map.of(
            "scene_registry",
            Map.of(
                    "version",
                    1,
                    "scenes",
                    Map.of("beta_chat", Map.of("app_id", "beta", "default", true))));

    @Test
    void edgeServiceBindRequiresKnownAppId() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "edge_r1",
                "poc-default",
                "edge",
                "lua-dsl",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "service", "bind_ref", Map.of("app_ids", List.of("unknown-app"))),
                Map.of("list_type", "deny", "keywords", List.of("x")),
                null,
                null);
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleBindScopeValidator.validateRouteUris(req, SCENE_METADATA));
    }

    @Test
    void edgeRouteBindRejected() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "edge_r2",
                "poc-default",
                "edge",
                "lua-dsl",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "route", "bind_ref", Map.of("scenes", List.of("beta_chat"))),
                Map.of("list_type", "deny", "keywords", List.of("x")),
                null,
                null);
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleBindScopeValidator.validateRouteUris(req, SCENE_METADATA));
    }
}
