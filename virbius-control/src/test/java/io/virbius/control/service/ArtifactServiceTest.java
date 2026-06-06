package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.virbius.control.domain.CumulativeDef;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.repository.CumulativeRepository;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArtifactServiceTest {

    private static final String TENANT = "default";

    @Mock
    private RegistryRepository registryRepo;

    @Mock
    private ListMetaRepository listMetaRepo;

    @Mock
    private CumulativeRepository cumulativeRepo;

    @Mock
    private TenantRolloutPolicyRepository policyRepository;

    private ArtifactService artifactService;

    @BeforeEach
    void setUp() {
        artifactService = new ArtifactService(
                "./target/test-data",
                registryRepo,
                listMetaRepo,
                cumulativeRepo,
                policyRepository,
                "",
                "");
    }

    @Test
    void gatewaySnapshotIncludesCumulativesAndScriptRules() {
        CumulativeDef globalDef = new CumulativeDef(
                TENANT, "user_req_1h_global", "global", "user_id", "rolling", 60, null, null, 0, "active");
        CumulativeDef routeDef = new CumulativeDef(
                TENANT, "chat_user_req_1h", "route", "user_id", "rolling", 60, null, null, 1, "active");

        when(cumulativeRepo.list(TENANT, "active")).thenReturn(List.of(globalDef, routeDef));
        when(listMetaRepo.listMeta(TENANT)).thenReturn(List.of());

        RuleRevision globalRule = scriptRule(
                "rl_global",
                "gateway",
                "function decide(ctx)\n  return getCumulative('user_req_1h_global') >= 100\nend",
                Map.of("bind_scope", "global"),
                80);
        RuleRevision routeRule = scriptRule(
                "rl_chat",
                "gateway",
                "function decide(ctx)\n  return getCumulative('chat_user_req_1h') >= 5\nend",
                Map.of("bind_scope", "route", "bind_ref", Map.of("uris", List.of("/v1/chat/completions"))),
                120);
        RuleRevision cloudRule = scriptRule(
                "cloud_rl",
                "cloud",
                "def decide(ctx) { getCumulative('user_req_1h_global') >= 1 }",
                Map.of("bind_scope", "global"),
                60);

        when(registryRepo.listCurrentRules(eq(TENANT), eq(null)))
                .thenReturn(List.of(globalRule, routeRule, cloudRule));
        when(registryRepo.listCurrentRules(eq(TENANT), eq("gateway")))
                .thenReturn(List.of(globalRule, routeRule));

        Map<String, Object> snap = artifactService.buildGatewaySnapshot(TENANT);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cumulatives = (List<Map<String, Object>>) snap.get("cumulatives");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) snap.get("script_rules");

        assertEquals(2, cumulatives.size());
        assertTrue(cumulatives.stream().allMatch(c -> c.containsKey("ingest_targets")));
        assertTrue(cumulatives.stream().allMatch(c -> c.containsKey("binding_rules")));
        assertFalse(snap.containsKey("cumulative_rules"));

        assertEquals(2, rules.size());
        assertEquals("route", rules.get(0).get("bind_scope"));
        @SuppressWarnings("unchecked")
        Map<String, Object> routeRef = (Map<String, Object>) rules.get(0).get("bind_ref");
        assertEquals(List.of("/v1/chat/completions"), routeRef.get("uris"));
    }

    @Test
    void bindingRulesUnionFromGatewayAndCloudRules() {
        CumulativeDef def = new CumulativeDef(
                TENANT, "chat_user_req_1h", "route", "user_id", "rolling", 60, null, null, 1, "active");
        when(cumulativeRepo.list(TENANT, "active")).thenReturn(List.of(def));
        when(listMetaRepo.listMeta(TENANT)).thenReturn(List.of());

        RuleRevision gw = scriptRule(
                "rl_gw",
                "gateway",
                "function decide(ctx)\n  return getCumulative('chat_user_req_1h') >= 1\nend",
                Map.of("bind_scope", "route", "bind_ref", Map.of("uris", List.of("/v1/chat/completions"))),
                100);
        RuleRevision cloud = scriptRule(
                "rl_cloud",
                "cloud",
                "def decide(ctx) { getCumulative('chat_user_req_1h') >= 1 }",
                Map.of("bind_scope", "global"),
                80);
        when(registryRepo.listCurrentRules(eq(TENANT), eq(null))).thenReturn(List.of(gw, cloud));
        when(registryRepo.listCurrentRules(eq(TENANT), eq("gateway"))).thenReturn(List.of(gw));

        Map<String, Object> snap = artifactService.buildGatewaySnapshot(TENANT);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cumulatives = (List<Map<String, Object>>) snap.get("cumulatives");
        assertEquals(1, cumulatives.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindingRules =
                (List<Map<String, Object>>) cumulatives.get(0).get("binding_rules");
        assertEquals(2, bindingRules.size());
        assertTrue(bindingRules.stream().anyMatch(b -> "global".equals(b.get("bind_scope"))));
        assertTrue(bindingRules.stream().anyMatch(b -> "route".equals(b.get("bind_scope"))));
    }

    @Test
    void ingestTargetsIncludeDefaultAndCloudBinding() {
        CumulativeDef def = new CumulativeDef(
                TENANT, "user_req_1h", null, "user_id", "rolling", 60, null, null, 0, "active");
        when(cumulativeRepo.list(TENANT, "active")).thenReturn(List.of(def));
        when(listMetaRepo.listMeta(TENANT)).thenReturn(List.of());
        when(registryRepo.listCurrentRules(eq(TENANT), eq("gateway"))).thenReturn(List.of());

        RuleRevision gw = scriptRule(
                "rl_gw",
                "gateway",
                "function decide(ctx)\n  return getCumulative('user_req_1h') >= 1\nend",
                Map.of("bind_scope", "global"),
                100);
        RuleRevision cloud = scriptRule(
                "rl_cloud",
                "cloud",
                "def decide(ctx) { getCumulative('user_req_1h') >= 1 }",
                Map.of("bind_scope", "global"),
                80);
        when(registryRepo.listCurrentRules(eq(TENANT), eq(null))).thenReturn(List.of(gw, cloud));

        Map<String, Object> snap = artifactService.buildGatewaySnapshot(TENANT);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cumulatives = (List<Map<String, Object>>) snap.get("cumulatives");
        assertEquals(1, cumulatives.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>) cumulatives.get(0).get("ingest_targets");
        assertFalse(targets.isEmpty());
        assertEquals("default", targets.get(0).get("kind"));
    }

    @Test
    void edgeManifestBlocksFilteredPerApp() {
        Map<String, Object> metadata = Map.of(
                "scene_registry",
                Map.of(
                        "version",
                        1,
                        "scenes",
                        Map.of(
                                "beta_chat",
                                Map.of("app_id", "beta", "default", true, "uris", List.of("/v1/chat")),
                                "medical-prod_chat",
                                Map.of(
                                        "app_id",
                                        "medical-prod",
                                        "default",
                                        true,
                                        "uris",
                                        List.of("/v1/chat")))));

        RuleRevision globalDeny = edgeRule(
                "edge_global_deny",
                Map.of(),
                Map.of("list_type", "deny", "keywords", List.of("bad")));
        RuleRevision medicalOnly = edgeRule(
                "edge_medical_only",
                Map.of("bind_scope", "service", "bind_ref", Map.of("app_ids", List.of("medical-prod"))),
                Map.of("list_type", "deny", "keywords", List.of("clinical-secret")));

        when(registryRepo.listCurrentRules(TENANT, "edge")).thenReturn(List.of(globalDeny, medicalOnly));

        List<Map<String, Object>> betaBlocks =
                artifactService.buildEdgeRuleBlocksForApp(TENANT, "beta", metadata);
        assertEquals(1, betaBlocks.size());
        assertEquals("edge_global_deny", betaBlocks.get(0).get("rule_id"));

        List<Map<String, Object>> medBlocks =
                artifactService.buildEdgeRuleBlocksForApp(TENANT, "medical-prod", metadata);
        assertEquals(2, medBlocks.size());
    }

    @Test
    void dlpRulesSeparatedFromKeywordRules() {
        Map<String, Object> metadata = Map.of(
                "scene_registry",
                Map.of("version", 1, "scenes", Map.of()));

        RuleRevision keyword = edgeRule(
                "edge_kw",
                Map.of(),
                Map.of("list_type", "deny", "keywords", List.of("bad")));
        RuleRevision dlp = dlpRule("edge_dlp_phone", Map.of("entity_type", "phone_cn"));

        when(registryRepo.listCurrentRules(TENANT, "edge")).thenReturn(List.of(keyword, dlp));

        List<Map<String, Object>> kwBlocks =
                artifactService.buildEdgeRuleBlocksForApp(TENANT, "beta", metadata);
        List<Map<String, Object>> dlpBlocks =
                artifactService.buildDlpRuleBlocksForApp(TENANT, "beta", metadata);

        assertEquals(1, kwBlocks.size());
        assertEquals("edge_kw", kwBlocks.get(0).get("rule_id"));
        assertEquals(1, dlpBlocks.size());
        assertEquals("edge_dlp_phone", dlpBlocks.get(0).get("rule_id"));
        assertEquals("allow", dlpBlocks.get(0).get("intent_action"));
        assertEquals(0, dlpBlocks.get(0).get("risk_score"));
    }

    private static RuleRevision dlpRule(String ruleId, Map<String, Object> body) {
        return new RuleRevision(
                TENANT,
                ruleId,
                1,
                "poc-default",
                "edge",
                "dlp-dsl",
                "DLP_PHONE",
                0,
                "allow",
                Map.of(),
                body,
                "dry_run",
                null,
                Instant.now(),
                Instant.now(),
                null);
    }

    private static RuleRevision edgeRule(
            String ruleId, Map<String, Object> scope, Map<String, Object> body) {
        return new RuleRevision(
                TENANT,
                ruleId,
                1,
                "poc-default",
                "edge",
                "lua-dsl",
                "REASON",
                100,
                "deny",
                scope,
                body,
                "dry_run",
                null,
                Instant.now(),
                Instant.now(),
                null);
    }

    private static RuleRevision scriptRule(
            String ruleId, String layer, String body, Map<String, Object> scope, int riskScore) {
        return new RuleRevision(
                TENANT,
                ruleId,
                1,
                "poc-default",
                layer,
                "gateway".equals(layer) ? "lua" : "groovy",
                "REASON",
                riskScore,
                "deny",
                scope,
                body,
                "dry_run",
                null,
                Instant.now(),
                Instant.now(),
                null);
    }
}
