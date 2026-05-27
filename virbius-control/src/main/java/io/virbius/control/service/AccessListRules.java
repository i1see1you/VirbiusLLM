package io.virbius.control.service;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.enums.AccessListDimension;
import io.virbius.control.domain.enums.AccessListPolarity;
import io.virbius.control.domain.enums.RuleStatus;
import io.virbius.control.domain.RiskScore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public final class AccessListRules {

    public static final String DEFAULT_BUNDLE = "poc-default";

    public static final String EDGE_CONTENT_DENY = "edge_l0_content_deny";
    public static final String EDGE_CONTENT_ALLOW = "edge_l0_content_allow";

    public static final String GW_SUBJECT_DENY = "gw_subject_network_deny";
    public static final String GW_SUBJECT_ALLOW = "gw_subject_network_allow";
    public static final String GW_CONTENT_DENY = "gw_content_deny";
    public static final String GW_CONTENT_ALLOW = "gw_content_allow";
    public static final String GW_REQUEST_DENY = "gw_request_param_deny";
    public static final String GW_REQUEST_ALLOW = "gw_request_param_allow";

    public static final String CLOUD_CONTENT_DENY = "cloud_l1_blacklist";
    public static final String CLOUD_CONTENT_ALLOW = "cloud_l1_whitelist";
    public static final String CLOUD_REQUEST_DENY = "cloud_l1_request_deny";
    public static final String CLOUD_REQUEST_ALLOW = "cloud_l1_request_allow";

    public static final String REASON_EDGE_CONTENT_DENY = "EDGE_CONTENT_KEYWORD_DENY";
    public static final String REASON_EDGE_CONTENT_ALLOW = "EDGE_CONTENT_KEYWORD_ALLOW";
    public static final String REASON_GW_USER_DENY = "GW_SUBJECT_USER_DENY";
    public static final String REASON_GW_DEVICE_DENY = "GW_SUBJECT_DEVICE_DENY";
    public static final String REASON_GW_IP_DENY = "GW_NETWORK_IP_DENY";
    public static final String REASON_GW_KEYWORD_DENY = "GW_CONTENT_KEYWORD_DENY";
    public static final String REASON_GW_CONTEXT_VAR_DENY = "GW_CONTEXT_VAR_DENY";
    public static final String REASON_CLOUD_CONTENT_DENY = "EDGE_KEYWORD_BLACKLIST";
    public static final String REASON_CLOUD_CONTENT_ALLOW = "EDGE_KEYWORD_WHITELIST";
    public static final String REASON_CLOUD_CONTEXT_VAR_DENY = "CLOUD_CONTEXT_VAR_DENY";

    private AccessListRules() {}

    public static RuleRevision contentRule(
            String tenantId,
            String ruleId,
            String layer,
            String runtime,
            String reasonCode,
            int riskScore,
            List<String> keywords,
            String listType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keywords", keywords);
        body.put("list_type", listType);
        return new RuleRevision(
                tenantId,
                ruleId,
                0,
                DEFAULT_BUNDLE,
                layer,
                runtime,
                reasonCode,
                riskScore,
                Map.of(),
                body,
                "dry_run",
                5,
                RuleStatus.ACTIVE.value(),
                null,
                null,
                null);
    }

    public static RuleRevision gatewaySubjectRule(
            String tenantId,
            AccessListPolarity polarity,
            List<String> userIds,
            List<String> deviceIds,
            List<String> ipCidrs) {
        Map<String, Object> subjects = new LinkedHashMap<>();
        subjects.put("user_ids", userIds);
        subjects.put("device_ids", deviceIds);
        subjects.put("ip_cidrs", ipCidrs);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list_type", polarity.value());
        body.put("subjects", subjects);
        String ruleId = polarity == AccessListPolarity.DENY ? GW_SUBJECT_DENY : GW_SUBJECT_ALLOW;
        String reason = polarity == AccessListPolarity.DENY ? REASON_GW_USER_DENY : REASON_EDGE_CONTENT_ALLOW;
        return new RuleRevision(
                tenantId,
                ruleId,
                0,
                DEFAULT_BUNDLE,
                "gateway",
                "lua",
                reason,
                polarity == AccessListPolarity.DENY ? RiskScore.DEFAULT : RiskScore.ALLOW,
                Map.of(),
                body,
                "dry_run",
                5,
                RuleStatus.ACTIVE.value(),
                null,
                null,
                null);
    }

    public static RuleRevision gatewayContentRule(
            String tenantId,
            String ruleId,
            String reasonCode,
            int riskScore,
            List<String> keywords,
            String listType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keywords", keywords);
        body.put("list_type", listType);
        return new RuleRevision(
                tenantId,
                ruleId,
                0,
                DEFAULT_BUNDLE,
                "gateway",
                "lua",
                reasonCode,
                riskScore,
                Map.of(),
                body,
                "dry_run",
                5,
                RuleStatus.ACTIVE.value(),
                null,
                null,
                null);
    }

    public static RuleRevision contextVarRule(
            String tenantId,
            String ruleId,
            String layer,
            String runtime,
            String reasonCode,
            int riskScore,
            List<String> vars,
            String listType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list_type", listType);
        body.put("vars", vars);
        return new RuleRevision(
                tenantId,
                ruleId,
                0,
                DEFAULT_BUNDLE,
                layer,
                runtime,
                reasonCode,
                riskScore,
                Map.of(),
                body,
                "dry_run",
                5,
                RuleStatus.ACTIVE.value(),
                null,
                null,
                null);
    }

    public static List<String> allRuleIds() {
        return List.of(
                EDGE_CONTENT_DENY,
                EDGE_CONTENT_ALLOW,
                GW_SUBJECT_DENY,
                GW_SUBJECT_ALLOW,
                GW_CONTENT_DENY,
                GW_CONTENT_ALLOW,
                GW_REQUEST_DENY,
                GW_REQUEST_ALLOW,
                CLOUD_CONTENT_DENY,
                CLOUD_CONTENT_ALLOW,
                CLOUD_REQUEST_DENY,
                CLOUD_REQUEST_ALLOW);
    }
}