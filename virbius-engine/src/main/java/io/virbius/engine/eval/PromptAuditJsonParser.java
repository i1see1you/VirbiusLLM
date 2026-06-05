package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public final class PromptAuditJsonParser {

    private static final Pattern JSON_BLOCK =
            Pattern.compile("\\{[^{}]*\"hit_rule\"[^{}]*\\}", Pattern.DOTALL);

    private final ObjectMapper mapper;

    public PromptAuditJsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public PromptAuditResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PromptAuditResult.miss();
        }
        String trimmed = raw.trim();
        PromptAuditResult direct = tryParse(trimmed);
        if (direct != null) {
            return direct;
        }
        Matcher m = JSON_BLOCK.matcher(trimmed);
        if (m.find()) {
            PromptAuditResult block = tryParse(m.group());
            if (block != null) {
                return block;
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            PromptAuditResult slice = tryParse(trimmed.substring(start, end + 1));
            if (slice != null) {
                return slice;
            }
        }
        return PromptAuditResult.miss();
    }

    private PromptAuditResult tryParse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            boolean hit = node.path("hit_rule").asBoolean(false);
            JsonNode tid = node.get("triggered_id");
            String triggeredId = tid == null || tid.isNull() ? null : tid.asText(null);
            if (triggeredId != null && (triggeredId.isBlank() || "null".equalsIgnoreCase(triggeredId))) {
                triggeredId = null;
            }
            String reason = node.path("reason").asText(null);
            return new PromptAuditResult(hit, triggeredId, reason);
        } catch (Exception e) {
            return null;
        }
    }

    public record PromptAuditResult(boolean hitRule, String triggeredId, String reason) {
        static PromptAuditResult miss() {
            return new PromptAuditResult(false, null, null);
        }
    }
}
