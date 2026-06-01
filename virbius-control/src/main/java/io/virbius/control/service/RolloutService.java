package io.virbius.control.service;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.domain.dto.response.RuleResponseMapper;
import io.virbius.control.domain.enums.RolloutState;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.repository.RolloutEventRepository;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RolloutService {

    private final RegistryRepository store;
    private final PromotionGateService gateService;
    private final RolloutEventRepository eventRepository;
    private final RuleExecutionSync ruleExecutionSync;

    public RolloutService(
            RegistryRepository store,
            PromotionGateService gateService,
            RolloutEventRepository eventRepository,
            RuleExecutionSync ruleExecutionSync) {
        this.store = store;
        this.gateService = gateService;
        this.eventRepository = eventRepository;
        this.ruleExecutionSync = ruleExecutionSync;
    }

    public Map<String, Object> applyRollout(
            String tenantId,
            String ruleId,
            String rolloutState,
            Integer canaryPercent,
            boolean force,
            String comment,
            String trigger,
            String operator) {
        RuleRevision current = store.getCurrentRule(tenantId, ruleId).orElseThrow();
        String from = RolloutStateHelper.stateOf(current);
        String to = RolloutStateHelper.normalize(rolloutState);
        RolloutStateHelper.validateTransition(from, to);
        RolloutStateHelper.validateCanaryPercent(to, canaryPercent);

        if (RolloutState.CANARY.value().equals(to) && RolloutState.CANARY.value().equals(from)) {
            // percent step within canary
        } else if (!from.equals(to) && requiresGate(from, to)) {
            gateService.requirePassOrForce(tenantId, current, to, canaryPercent, force, comment);
        }

        RuleRevision updated = store.updateRollout(tenantId, ruleId, to, canaryPercent);
        eventRepository.recordEvent(
                tenantId, ruleId, updated.ruleRevision(), to, canaryPercent, trigger, operator);
        ruleExecutionSync.afterRolloutChange(tenantId, current, updated);
        return RuleResponseMapper.toDetail(updated);
    }

    public Map<String, Object> publish(String tenantId, String ruleId) {
        return applyRollout(
                tenantId, ruleId, RolloutState.DRY_RUN.value(), null, false, null, "publish", "admin");
    }

    public Map<String, Object> rollback(String tenantId, String ruleId) {
        return applyRollout(
                tenantId, ruleId, RolloutState.DRY_RUN.value(), null, false, null, "rollback", "admin");
    }

    public Map<String, Object> disable(String tenantId, String ruleId) {
        return applyRollout(
                tenantId, ruleId, RolloutState.DISABLED.value(), null, false, null, "manual", "admin");
    }

    public Map<String, Object> recover(String tenantId, String ruleId) {
        return applyRollout(
                tenantId, ruleId, RolloutState.DRAFT.value(), null, false, null, "recover", "admin");
    }

    public Map<String, Object> evaluate(
            String tenantId, String ruleId, String targetState, Integer canaryPercent) {
        RuleRevision current = store.getCurrentRule(tenantId, ruleId).orElseThrow();
        Map<String, Object> result = gateService.evaluate(tenantId, current, targetState, canaryPercent);
        gateService.recordEvaluateLog(tenantId, ruleId, result);
        return result;
    }

    private static boolean requiresGate(String from, String to) {
        if (RolloutState.DRY_RUN.value().equals(from) && RolloutState.CANARY.value().equals(to)) {
            return true;
        }
        if (RolloutState.CANARY.value().equals(from)
                && (RolloutState.CANARY.value().equals(to) || RolloutState.FULL.value().equals(to))) {
            return true;
        }
        return false;
    }
}
