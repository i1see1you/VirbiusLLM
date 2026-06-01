package io.virbius.control.service;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RolloutStateHelper;
import io.virbius.control.domain.TenantRolloutPolicy;
import io.virbius.control.domain.enums.RolloutState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.repository.RolloutMetricsRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PromotionGateService {

    private final TenantRolloutPolicyRepository policyRepository;
    private final RolloutMetricsRepository metricsRepository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PromotionGateService(
            TenantRolloutPolicyRepository policyRepository,
            RolloutMetricsRepository metricsRepository,
            JdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.policyRepository = policyRepository;
        this.metricsRepository = metricsRepository;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, Object> evaluate(
            String tenantId, RuleRevision current, String targetState, Integer targetCanaryPercent) {
        TenantRolloutPolicy policy = policyRepository.getOrDefault(tenantId);
        String from = RolloutStateHelper.stateOf(current);
        String to = RolloutStateHelper.normalize(targetState);
        List<String> reasons = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        if (RolloutState.DRY_RUN.value().equals(from) && RolloutState.FULL.value().equals(to)) {
            reasons.add("dry_run -> full is permanently forbidden");
        }

        if (RolloutState.DRY_RUN.value().equals(from) && RolloutState.CANARY.value().equals(to)) {
            double dryRunHours = hoursSince(current.modifiedAt());
            metrics.put("dry_run_hours", dryRunHours);
            if (dryRunHours < policy.minDryRunHours()) {
                reasons.add("dry_run_hours=" + dryRunHours + " < min_dry_run_hours=" + policy.minDryRunHours());
            }
            long review24h = metricsRepository.countReview24h(tenantId, current.ruleId());
            metrics.put("review_24h", review24h);
            if (review24h < policy.minReviewCount()) {
                reasons.add("review_count_24h=" + review24h + " < min_review_count=" + policy.minReviewCount());
            }
            long total24h = metricsRepository.countTotalRequests24h(tenantId);
            metrics.put("total_requests_24h", total24h);
            if (total24h > 0) {
                double reviewRate = (double) review24h / total24h;
                metrics.put("review_rate", reviewRate);
                if (reviewRate > policy.maxReviewRate()) {
                    reasons.add("review_rate=" + reviewRate + " > max_review_rate=" + policy.maxReviewRate());
                }
            }
            if (targetCanaryPercent != null && !targetCanaryPercent.equals(policy.canaryLadder().get(0))) {
                reasons.add("canary_percent must be first ladder step " + policy.canaryLadder().get(0));
            }
        }

        if (RolloutState.CANARY.value().equals(from)
                && (RolloutState.CANARY.value().equals(to) || RolloutState.FULL.value().equals(to))) {
            double stepHours = hoursSince(current.modifiedAt());
            metrics.put("hours_at_current_step", stepHours);
            if (stepHours < policy.minHoursPerStep()) {
                reasons.add("hours_at_current_step=" + stepHours + " < min_hours_per_step=" + policy.minHoursPerStep());
            }
            long blockBucket = metricsRepository.countBlockInCanary24h(tenantId, current.ruleId());
            metrics.put("block_in_bucket_24h", blockBucket);
            if (blockBucket < policy.minBlockSamplesPerStep()) {
                reasons.add(
                        "block_in_bucket_24h=" + blockBucket + " < min_block_samples_per_step="
                                + policy.minBlockSamplesPerStep());
            }
        }

        boolean pass = reasons.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pass", pass);
        result.put("reasons", reasons);
        result.put("metrics", metrics);
        result.put("from_state", from);
        result.put("to_state", to);
        result.put("target_canary_percent", targetCanaryPercent);
        result.put("suggested_next", suggestedNext(current, policy));
        return result;
    }

    public void requirePassOrForce(
            String tenantId,
            RuleRevision current,
            String targetState,
            Integer targetCanaryPercent,
            boolean force,
            String comment) {
        Map<String, Object> eval = evaluate(tenantId, current, targetState, targetCanaryPercent);
        boolean pass = Boolean.TRUE.equals(eval.get("pass"));
        TenantRolloutPolicy policy = policyRepository.getOrDefault(tenantId);
        boolean forced = force && policy.allowForce() && comment != null && !comment.isBlank();
        recordGateLog(
                tenantId,
                current.ruleId(),
                (String) eval.get("from_state"),
                (String) eval.get("to_state"),
                pass || forced,
                eval,
                forced ? "admin" : "admin",
                forced ? comment : null);
        if (pass) {
            return;
        }
        if (forced) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) eval.get("reasons");
        throw new BusinessException(409, "GATE_FAILED: " + String.join("; ", reasons));
    }

    public void recordEvaluateLog(String tenantId, String ruleId, Map<String, Object> eval) {
        recordGateLog(
                tenantId,
                ruleId,
                (String) eval.get("from_state"),
                (String) eval.get("to_state"),
                Boolean.TRUE.equals(eval.get("pass")),
                eval,
                "admin",
                null);
    }

    private void recordGateLog(
            String tenantId,
            String ruleId,
            String fromState,
            String toState,
            boolean pass,
            Map<String, Object> eval,
            String operator,
            String comment) {
        try {
            jdbc.update(
                    """
                    INSERT INTO tb_rule_gate_log (
                      tenant_id, rule_id, from_state, to_state, pass, reasons_json, metrics_snapshot_json,
                      operator, comment
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenantId,
                    ruleId,
                    fromState,
                    toState,
                    pass ? 1 : 0,
                    mapper.writeValueAsString(eval.get("reasons")),
                    mapper.writeValueAsString(eval.get("metrics")),
                    operator,
                    comment);
        } catch (JsonProcessingException ignored) {
        }
    }

    private static Map<String, Object> suggestedNext(RuleRevision current, TenantRolloutPolicy policy) {
        String from = RolloutStateHelper.stateOf(current);
        List<Integer> ladder = policy.canaryLadder();
        if (RolloutState.DRAFT.value().equals(from) || RolloutState.DISABLED.value().equals(from)) {
            return null;
        }
        if (RolloutState.DRY_RUN.value().equals(from)) {
            if (ladder.isEmpty()) {
                return null;
            }
            Map<String, Object> next = new LinkedHashMap<>();
            next.put("rollout_state", RolloutState.CANARY.value());
            next.put("canary_percent", ladder.get(0));
            return next;
        }
        if (RolloutState.CANARY.value().equals(from)) {
            Integer percent = current.canaryPercent();
            int idx = ladder.indexOf(percent != null ? percent : 0);
            if (idx < 0) {
                idx = 0;
            }
            if (idx + 1 >= ladder.size()) {
                return null;
            }
            int step = ladder.get(idx + 1);
            Map<String, Object> next = new LinkedHashMap<>();
            if (step >= 100) {
                next.put("rollout_state", RolloutState.FULL.value());
                next.put("canary_percent", null);
            } else {
                next.put("rollout_state", RolloutState.CANARY.value());
                next.put("canary_percent", step);
            }
            return next;
        }
        return null;
    }

    private static double hoursSince(Instant from) {
        if (from == null) {
            return 0;
        }
        return Duration.between(from, Instant.now()).toMinutes() / 60.0;
    }
}
