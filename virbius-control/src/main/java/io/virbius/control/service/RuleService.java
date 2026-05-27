package io.virbius.control.service;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RiskScore;
import io.virbius.control.domain.RuleStatusHelper;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.domain.dto.response.RuleResponseMapper;
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
            RuleStatusHelper.requireActive(existing.get());
        }
        String ruleStatus = existing.map(RuleRevision::ruleStatus).orElse(RuleStatus.ACTIVE.value());
        RuleRevision draft = new RuleRevision(
                tenantId,
                req.ruleId(),
                0,
                req.bundleId() != null ? req.bundleId() : "poc-default",
                req.layer(),
                req.runtime(),
                req.reasonCode(),
                RiskScore.normalize(req.riskScore()),
                req.scope() != null ? req.scope() : Map.of(),
                req.body(),
                "dry_run",
                5,
                ruleStatus,
                null,
                null,
                null);
        try {
            groovyRuleValidator.validateRevision(draft);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        return RuleResponseMapper.toDetail(store.upsertRule(tenantId, draft));
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
        EnforceMode.parse(enforceMode);
        RuleRevision r = store.updateRuntime(tenantId, ruleId, enforceMode, canaryPercent);
        return RuleResponseMapper.toDetail(r);
    }

    public Map<String, Object> updateRuleStatus(String tenantId, String ruleId, String ruleStatus) {
        RuleRevision updated = store.updateRuleStatus(tenantId, ruleId, ruleStatus);
        accessListService.syncRules(tenantId);
        publishService.runtimeSnapshot(tenantId);
        return RuleResponseMapper.toDetail(updated);
    }
}
