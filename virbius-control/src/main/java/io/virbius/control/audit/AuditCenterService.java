package io.virbius.control.audit;

import io.virbius.control.service.RolloutDashboardService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuditCenterService {

    private final RolloutDashboardService dashboardService;
    private final AuditAllowLogWriter controlAllowLog;
    private final Path gatewayAllowLogPath;
    private final Path engineAllowLogPath;

    public AuditCenterService(
            RolloutDashboardService dashboardService,
            AuditAllowLogWriter controlAllowLog,
            @Value("${virbius.audit.gateway-allow-log-path:${virbius.data-dir:./data}/gateway-audit-allow.jsonl}")
                    String gatewayAllowLogPath,
            @Value("${virbius.audit.engine-allow-log-path:${VIRBIUS_ENGINE_AUDIT_ALLOW:/tmp/virbius/engine-audit-allow.jsonl}}")
                    String engineAllowLogPath) {
        this.dashboardService = dashboardService;
        this.controlAllowLog = controlAllowLog;
        this.gatewayAllowLogPath = Path.of(gatewayAllowLogPath);
        this.engineAllowLogPath = Path.of(engineAllowLogPath);
    }

    public Map<String, Object> traceDetail(String tenantId, String traceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trace_id", traceId);
        out.put("tenant_id", tenantId);
        List<Map<String, Object>> dbEvents = dashboardService.trace(tenantId, traceId);
        out.put("db_events", dbEvents);
        out.put("db_count", dbEvents.size());

        List<Map<String, Object>> allowEvents = new ArrayList<>();
        allowEvents.addAll(AuditAllowLogWriter.findInFile(gatewayAllowLogPath, traceId, 100));
        allowEvents.addAll(AuditAllowLogWriter.findInFile(engineAllowLogPath, traceId, 100));
        allowEvents.addAll(controlAllowLog.findByTraceId(traceId, 100));
        allowEvents.sort((a, b) -> String.valueOf(a.get("intercepted_at"))
                .compareTo(String.valueOf(b.get("intercepted_at"))));
        out.put("allow_log_events", allowEvents);
        out.put("allow_log_count", allowEvents.size());

        List<Map<String, Object>> hints = new ArrayList<>();
        hints.add(AuditAllowLogWriter.logHint("gateway", gatewayAllowLogPath));
        hints.add(AuditAllowLogWriter.logHint("engine", engineAllowLogPath));
        hints.add(AuditAllowLogWriter.logHint("edge/control", controlAllowLog.path()));
        out.put("allow_log_files", hints);
        out.put(
                "note",
                "tb_audit_events only stores review/block/captcha; allow records can be found in allow_log_events or the JSONL files above.");
        return out;
    }
}
