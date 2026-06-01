package io.virbius.control.repository;

import io.virbius.control.domain.CumulativeDef;
import io.virbius.policy.CumulativeWindow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCumulativeRepository implements CumulativeRepository {

    private final JdbcTemplate jdbc;

    public JdbcCumulativeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CumulativeDef> MAPPER = (rs, rowNum) -> map(rs);

    private static CumulativeDef map(ResultSet rs) throws SQLException {
        Integer windowMinutes = (Integer) rs.getObject("window_minutes");
        Integer windowHours = (Integer) rs.getObject("window_hours");
        return new CumulativeDef(
                rs.getString("tenant_id"),
                rs.getString("cumulative_name"),
                rs.getString("description"),
                rs.getString("dimension"),
                rs.getString("window_kind"),
                windowMinutes,
                windowHours,
                rs.getString("timezone"),
                rs.getInt("threshold"),
                rs.getString("compare_op"),
                rs.getString("on_exceed_suggest"),
                rs.getInt("on_exceed_risk_score"),
                rs.getString("on_exceed_reason_code"),
                rs.getInt("priority"),
                rs.getString("status"));
    }

    @Override
    public List<CumulativeDef> list(String tenantId, String status) {
        String sql =
                """
                SELECT tenant_id, cumulative_name, description, dimension, window_kind, window_minutes,
                       window_hours, timezone, threshold, compare_op, on_exceed_suggest, on_exceed_risk_score,
                       on_exceed_reason_code, priority, status
                FROM tb_cumulative WHERE tenant_id = ?
                """;
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql += " AND status = ?";
            args.add(status);
        }
        sql += " ORDER BY priority, cumulative_name";
        return jdbc.query(sql, MAPPER, args.toArray());
    }

    @Override
    public Optional<CumulativeDef> get(String tenantId, String cumulativeName) {
        List<CumulativeDef> rows = jdbc.query(
                """
                SELECT tenant_id, cumulative_name, description, dimension, window_kind, window_minutes,
                       window_hours, timezone, threshold, compare_op, on_exceed_suggest, on_exceed_risk_score,
                       on_exceed_reason_code, priority, status
                FROM tb_cumulative WHERE tenant_id = ? AND cumulative_name = ?
                """,
                MAPPER,
                tenantId,
                cumulativeName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void upsert(CumulativeDef def) {
        validate(def);
        int updated = jdbc.update(
                """
                UPDATE tb_cumulative SET description=?, dimension=?, window_kind=?, window_minutes=?, window_hours=?,
                    timezone=?, threshold=?, compare_op=?, on_exceed_suggest=?, on_exceed_risk_score=?,
                    on_exceed_reason_code=?, priority=?, status=?, updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND cumulative_name=?
                """,
                def.description(),
                def.dimension(),
                def.windowKind(),
                def.windowMinutes(),
                def.windowHours(),
                def.timezone(),
                def.threshold(),
                def.compareOp(),
                def.onExceedSuggest(),
                def.onExceedRiskScore(),
                def.onExceedReasonCode(),
                def.priority(),
                def.status(),
                def.tenantId(),
                def.cumulativeName());
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO tb_cumulative (
                      tenant_id, cumulative_name, description, dimension, window_kind, window_minutes,
                      window_hours, timezone, threshold, compare_op, on_exceed_suggest, on_exceed_risk_score,
                      on_exceed_reason_code, priority, status)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    def.tenantId(),
                    def.cumulativeName(),
                    def.description(),
                    def.dimension(),
                    def.windowKind(),
                    def.windowMinutes(),
                    def.windowHours(),
                    def.timezone(),
                    def.threshold(),
                    def.compareOp(),
                    def.onExceedSuggest(),
                    def.onExceedRiskScore(),
                    def.onExceedReasonCode(),
                    def.priority(),
                    def.status());
        }
    }

    @Override
    public void delete(String tenantId, String cumulativeName) {
        jdbc.update("DELETE FROM tb_cumulative WHERE tenant_id = ? AND cumulative_name = ?", tenantId, cumulativeName);
    }

    private static void validate(CumulativeDef def) {
        if ("calendar_day".equalsIgnoreCase(def.windowKind())) {
            if (def.timezone() == null || def.timezone().isBlank()) {
                throw new IllegalArgumentException("calendar_day requires timezone");
            }
        } else {
            CumulativeWindow.windowMinutes(def.windowKind(), def.windowMinutes(), def.windowHours());
        }
        if (def.windowMinutes() != null && def.windowHours() != null) {
            throw new IllegalArgumentException("window_minutes and window_hours are mutually exclusive");
        }
    }
}
