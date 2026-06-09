package io.virbius.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Append-only JSONL for allow audit events (not stored in tb_audit_events). */
@Component
public class AuditAllowLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditAllowLogWriter.class);

    private final Path allowLogPath;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditAllowLogWriter(
            @Value("${virbius.audit.allow-log-path:${virbius.data-dir:./data}/audit-allow.jsonl}") String path) {
        this.allowLogPath = Path.of(path);
    }

    public void append(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(allowLogPath.getParent());
            String json = mapper.writeValueAsString(event);
            Files.writeString(allowLogPath, json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("audit allow log write failed: {}", e.getMessage());
        }
    }

    public void appendJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(allowLogPath.getParent());
            Files.writeString(allowLogPath, json.strip() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("audit allow log write failed: {}", e.getMessage());
        }
    }

    public Path path() {
        return allowLogPath;
    }

    public List<Map<String, Object>> findByTraceId(String traceId, int limit) {
        return findInFile(allowLogPath, traceId, limit);
    }

    public static List<Map<String, Object>> findInFile(Path path, String traceId, int limit) {
        if (traceId == null || traceId.isBlank() || path == null || !Files.isRegularFile(path)) {
            return List.of();
        }
        ObjectMapper om = new ObjectMapper();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank() || !line.contains(traceId)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> event = om.readValue(line, Map.class);
                if (traceId.equals(String.valueOf(event.get("trace_id")))) {
                    out.add(event);
                }
            }
        } catch (IOException e) {
            log.warn("audit allow log read failed ({}): {}", path, e.getMessage());
        }
        if (out.size() > limit) {
            return out.subList(out.size() - limit, out.size());
        }
        return out;
    }

    public static boolean isAllowAction(Map<String, Object> event) {
        if (event == null) {
            return false;
        }
        return "allow".equalsIgnoreCase(String.valueOf(event.get("effective_action")));
    }

    public static Map<String, Object> logHint(String label, Path path) {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("label", label);
        hint.put("path", path != null ? path.toString() : "");
        hint.put("exists", path != null && Files.isRegularFile(path));
        return hint;
    }
}
