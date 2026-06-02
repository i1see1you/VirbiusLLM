package io.virbius.control.service;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RiskScore;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.domain.dto.response.RuleResponseMapper;
import io.virbius.control.domain.enums.IntentAction;
import io.virbius.control.domain.enums.RolloutState;
import io.virbius.control.groovy.GroovyRuleValidator;
import io.virbius.control.policy.RuleBodyRefs;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.repository.RolloutEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class RuleService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RegistryRepository store;
    private final GroovyRuleValidator groovyRuleValidator;
    private final RolloutEventRepository eventRepository;
    private final RuleExecutionSync ruleExecutionSync;

    public RuleService(
            RegistryRepository store,
            GroovyRuleValidator groovyRuleValidator,
            RolloutEventRepository eventRepository,
            RuleExecutionSync ruleExecutionSync) {
        this.store = store;
        this.groovyRuleValidator = groovyRuleValidator;
        this.eventRepository = eventRepository;
        this.ruleExecutionSync = ruleExecutionSync;
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
            RolloutStateHelper.requireNotDisabled(existing.get());
        }
        String rolloutState =
                existing.map(RuleRevision::rolloutState).orElse(RolloutState.DRAFT.value());
        Integer canaryPercent = existing.map(RuleRevision::canaryPercent).orElse(null);

        int normalizedRisk = RiskScore.normalize(req.riskScore());
        String intentAction = req.intentAction() != null && !req.intentAction().isBlank()
                ? IntentAction.parse(req.intentAction()).value()
                : existing.map(RuleRevision::intentAction)
                        .orElse(IntentAction.defaultForRisk(normalizedRisk).value());

        if (existing.isPresent() && contentChanged(existing.get(), req, normalizedRisk, intentAction)) {
            if (RolloutStateHelper.inExecutionPlane(existing.get())) {
                rolloutState = RolloutState.DRAFT.value();
                canaryPercent = null;
            }
        }

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
                rolloutState,
                canaryPercent,
                null,
                null,
                null);
        try {
            groovyRuleValidator.validateRevision(draft);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if ("cumulative".equals(req.runtime()) || "list_match".equals(req.runtime())) {
            throw new IllegalArgumentException("runtime " + req.runtime()
                    + " removed; use lua (gateway) or groovy (cloud) script rules");
        }
        RuleRevision before = existing.orElse(null);
        RuleRevision saved = store.upsertRule(tenantId, draft);
        if (before != null
                && RolloutStateHelper.inExecutionPlane(before)
                && RolloutState.DRAFT.value().equals(saved.rolloutState())) {
            eventRepository.recordEvent(
                    tenantId,
                    saved.ruleId(),
                    saved.ruleRevision(),
                    saved.rolloutState(),
                    null,
                    "body_change",
                    "admin");
        }
        if (before != null) {
            ruleExecutionSync.afterRolloutChange(tenantId, before, saved);
        } else if (RolloutStateHelper.inExecutionPlane(saved)) {
            ruleExecutionSync.afterContentChange(tenantId, saved);
        }
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

    @Deprecated
    public Map<String, Object> updateRuntime(String tenantId, String ruleId, String enforceMode, Integer canaryPercent) {
        RuleRevision r = store.updateRuntime(tenantId, ruleId, enforceMode, canaryPercent);
        ruleExecutionSync.afterRolloutChange(tenantId, r, r);
        return RuleResponseMapper.toDetail(r);
    }

    @Deprecated
    public Map<String, Object> updateRuleStatus(String tenantId, String ruleId, String ruleStatus) {
        RuleRevision current = store.getCurrentRule(tenantId, ruleId).orElseThrow();
        RuleRevision updated = store.updateRuleStatus(tenantId, ruleId, ruleStatus);
        ruleExecutionSync.afterRolloutChange(tenantId, current, updated);
        return RuleResponseMapper.toDetail(updated);
    }

    private boolean contentChanged(
            RuleRevision existing, UpsertRuleRequest req, int riskScore, String intentAction) {
        if (!Objects.equals(existing.layer(), req.layer())
                || !Objects.equals(existing.runtime(), req.runtime())
                || !Objects.equals(existing.reasonCode(), req.reasonCode())
                || existing.riskScore() != riskScore
                || !Objects.equals(existing.intentAction(), intentAction)) {
            return true;
        }
        String oldBody = toJson(existing.body());
        String newBody = toJson(req.body());
        return !Objects.equals(oldBody, newBody);
    }

    private static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
