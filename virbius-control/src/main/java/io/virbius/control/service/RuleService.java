package io.virbius.control.service;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RiskScore;
import io.virbius.control.domain.RuleStatusHelper;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.domain.dto.response.RuleResponseMapper;
import io.virbius.control.domain.enums.IntentAction;
import io.virbius.control.domain.enums.EnforceMode;
import io.virbius.control.domain.enums.RuleStatus;
import io.virbius.control.groovy.GroovyRuleValidator;
import io.virbius.control.repository.RegistryRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class RuleService {

    private final RegistryRepository store;
    private final GroovyRuleValidator groovyRuleValidator;
    private final AccessListService accessListService;
    private final PublishService publishService;

    public RuleService(
            RegistryRepository store,
            GroovyRuleValidator groovyRuleValidator,
            AccessListService accessListService,
            PublishService publishService) {
        this.store = store;
        this.groovyRuleValidator = groovyRuleValidator;
        this.accessListService = accessListService;
        this.publishService = publishService;
    }

    public List<Map<String, Object>> listRules(String tenantId, String layer) {
        return store.listCurrentRules(tenantId, layer).stream()
                .map(RuleResponseMapper::toSummary)
                .toList();
    }

    public Map<String, Object> upsertRule(String tenantId, UpsertRuleRequest req) {
        if (req.riskScore() != null
                && (req.riskScore() < RiskScore.MIN || req.riskScore() > RiskScore.MAX)) {
            throw new IllegalArgumentException("risk_score must be between " + RiskScore.MIN + " and " + RiskScore.MAX);
        }
        Optional<RuleRevision> existing = store.getCurrentRule(tenantId, req.ruleId());
        if (existing.isPresent()) {
            RuleStatusHelper.requireEditable(existing.get());
        }
        String ruleStatus =
                existing.map(RuleRevision::ruleStatus).orElse(RuleStatus.DRAFT.value());
        int normalizedRisk = RiskScore.normalize(req.riskScore());
        String intentAction = req.intentAction() != null && !req.intentAction().isBlank()
                ? IntentAction.parse(req.intentAction()).value()
                : existing.map(RuleRevision::intentAction)
                        .orElse(IntentAction.defaultForRisk(normalizedRisk).value());
        RuleRevision draft = new RuleRevision(
                tenantId,
                req.ruleId(),
                0,
                req.bundleId() != null ? req.bundleId() : "poc-default",
                req.layer(),
                req.runtime(),
                req.reasonCode(),
                normalizedRisk,
                intentAction,
                req.scope() != null ? req.scope() : Map.of(),
                req.body(),
                existing.map(RuleRevision::enforceMode).orElse("dry_run"),
                existing.map(RuleRevision::canaryPercent).orElse(null),
                ruleStatus,
                null,
                null,
                null);
        try {
            groovyRuleValidator.validateRevision(draft);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        RuleRevision saved = store.upsertRule(tenantId, draft);
        syncArtifactsForActiveRule(tenantId, saved);
        return RuleResponseMapper.toDetail(saved);
    }

    public Map<String, Object> getRule(String tenantId, String ruleId) {
        RuleRevision r = store.getCurrentRule(tenantId, ruleId)
                .orElseThrow(() -> new IllegalArgumentException("rule not found: " + ruleId));
        return RuleResponseMapper.toDetail(r);
    }

    public List<Map<String, Object>> listRevisions(String tenantId, String ruleId) {
        return store.listRuleRevisions(tenantId, ruleId).stream()
                .map(RuleResponseMapper::toSummary)
                .toList();
    }

    public Map<String, Object> getRevision(String tenantId, String ruleId, int revision) {
        RuleRevision r = store.getRuleRevision(tenantId, ruleId, revision)
                .orElseThrow(() -> new IllegalArgumentException("revision not found"));
        return RuleResponseMapper.toDetail(r);
    }

    public Map<String, Object> updateRuntime(String tenantId, String ruleId, String enforceMode, Integer canaryPercent) {
        EnforceMode mode = EnforceMode.parse(enforceMode);
        if (mode == EnforceMode.CANARY
                && (canaryPercent == null || canaryPercent < 1 || canaryPercent > 100)) {
            throw new IllegalArgumentException("canary_percent required (1-100) when enforce_mode=canary");
        }
        RuleRevision current = store.getCurrentRule(tenantId, ruleId).orElseThrow();
        RuleStatusHelper.requireActive(current);
        RuleRevision r = store.updateRuntime(tenantId, ruleId, enforceMode, canaryPercent);
        syncArtifactsForActiveRule(tenantId, r);
        return RuleResponseMapper.toDetail(r);
    }

    public Map<String, Object> updateRuleStatus(String tenantId, String ruleId, String ruleStatus) {
        RuleRevision current = store.getCurrentRule(tenantId, ruleId).orElseThrow();
        String normalized = RuleStatusHelper.normalize(ruleStatus);
        RuleStatusHelper.validateTransition(RuleStatusHelper.statusOf(current), normalized);
        boolean wasActive = RuleStatusHelper.isActive(current);
        RuleRevision updated = store.updateRuleStatus(tenantId, ruleId, normalized);
        if (wasActive || RuleStatusHelper.isActive(updated)) {
            refreshExecutionPlane(tenantId);
        }
        return RuleResponseMapper.toDetail(updated);
    }

    private void syncArtifactsForActiveRule(String tenantId, RuleRevision rule) {
        if (!RuleStatusHelper.isActive(rule)) {
            return;
        }
        if ("list_match".equals(rule.runtime()) || "cumulative".equals(rule.runtime())) {
            accessListService.refreshArtifacts(tenantId);
        }
        if ("cloud".equals(rule.layer())
                && ("list_match".equals(rule.runtime()) || "cumulative".equals(rule.runtime()))) {
            publishService.runtimeSnapshot(tenantId);
        }
    }

    private void refreshExecutionPlane(String tenantId) {
        accessListService.refreshArtifacts(tenantId);
        publishService.runtimeSnapshot(tenantId);
    }
}
