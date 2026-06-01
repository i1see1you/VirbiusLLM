package io.virbius.control.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.virbius.control.domain.CumulativeDef;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.repository.CumulativeRepository;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.policy.CumulativeWindow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Enrich list_match / cumulative rules for engine cache. */
@Component
public class PolicyMaterializer {

    private final ListMetaRepository listMetaRepo;
    private final CumulativeRepository cumulativeRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public PolicyMaterializer(ListMetaRepository listMetaRepo, CumulativeRepository cumulativeRepo) {
        this.listMetaRepo = listMetaRepo;
        this.cumulativeRepo = cumulativeRepo;
    }

    public RuleRevision materialize(String tenantId, RuleRevision rule) {
        if ("list_match".equals(rule.runtime())) {
            return materializeListMatch(tenantId, rule);
        }
        if ("cumulative".equals(rule.runtime())) {
            return materializeCumulative(tenantId, rule);
        }
        return rule;
    }

    public List<RuleRevision> materializeAll(String tenantId, List<RuleRevision> rules) {
        return rules.stream().map(r -> materialize(tenantId, r)).toList();
    }

    private RuleRevision materializeListMatch(String tenantId, RuleRevision rule) {
        RuleBodyRefs refs = RuleBodyRefs.parse(rule.body());
        if (refs.listName() == null || refs.listName().isBlank()) {
            throw new IllegalArgumentException("list_match rule requires body.list_name");
        }
        var meta = listMetaRepo
                .getMeta(tenantId, refs.listName())
                .orElseThrow(() -> new IllegalArgumentException("list not found: " + refs.listName()));
        List<String> entryValues = listMetaRepo.listEntries(tenantId, refs.listName()).stream()
                .map(e -> e.value())
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list_name", refs.listName());
        body.put("list_type", rule.riskScore() <= 0 ? "allow" : "deny");
        body.put("dimension", meta.dimension());
        if ("keyword".equalsIgnoreCase(meta.dimension())) {
            body.put("keywords", entryValues);
        } else if (meta.dimension().startsWith("var") || "var".equals(meta.dimension())) {
            body.put("vars", entryValues);
        } else {
            body.put("values", entryValues);
        }
        putValueSource(body, refs);
        return copyWithBody(rule, body);
    }

    private RuleRevision materializeCumulative(String tenantId, RuleRevision rule) {
        RuleBodyRefs refs = RuleBodyRefs.parse(rule.body());
        if (refs.cumulativeName() == null || refs.cumulativeName().isBlank()) {
            throw new IllegalArgumentException("cumulative rule requires body.cumulative_name");
        }
        CumulativeDef def = cumulativeRepo
                .get(tenantId, refs.cumulativeName())
                .orElseThrow(() -> new IllegalArgumentException("cumulative not found: " + refs.cumulativeName()));
        int wMin = CumulativeWindow.windowMinutes(def.windowKind(), def.windowMinutes(), def.windowHours());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cumulative_name", def.cumulativeName());
        body.put("dimension", def.dimension());
        body.put("window_kind", def.windowKind());
        body.put("window_minutes", def.windowMinutes());
        body.put("window_hours", def.windowHours());
        body.put("timezone", def.timezone());
        body.put("threshold", def.threshold());
        body.put("compare_op", def.compareOp());
        body.put("W_minutes", wMin);
        putValueSource(body, refs);
        return copyWithBody(rule, body);
    }

    private static void putValueSource(Map<String, Object> body, RuleBodyRefs refs) {
        if (refs.valueSource() == null) {
            return;
        }
        io.virbius.policy.ValueSource vs = refs.valueSource();
        Map<String, Object> m = new LinkedHashMap<>();
        String kind =
                switch (vs.kind()) {
                    case REQUEST_FIELD -> "request_field";
                    case VAR -> "var";
                    case HEADER -> "header";
                    case QUERY -> "query";
                    case CONTENT -> "content";
                    case LITERAL -> "literal";
                    default -> "default";
                };
        m.put("kind", kind);
        if (vs.ref() != null) {
            m.put("ref", vs.ref());
        }
        if (vs.literalValue() != null) {
            m.put("value", vs.literalValue());
        }
        body.put("value_source", m);
    }

    private static RuleRevision copyWithBody(RuleRevision rule, Map<String, Object> body) {
        return new RuleRevision(
                rule.tenantId(),
                rule.ruleId(),
                rule.ruleRevision(),
                rule.bundleId(),
                rule.layer(),
                rule.runtime(),
                rule.reasonCode(),
                rule.riskScore(),
                rule.intentAction(),
                rule.scope(),
                body,
                rule.rolloutState(),
                rule.canaryPercent(),
                rule.modifiedAt(),
                rule.effectiveFrom(),
                rule.effectiveTo());
    }
}
