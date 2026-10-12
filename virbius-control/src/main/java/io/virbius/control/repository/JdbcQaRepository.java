package io.virbius.control.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.QaEntry;
import io.virbius.control.domain.QaMeta;
import io.virbius.control.domain.QaPublishJob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQaRepository implements QaRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcQaRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String toJson(ObjectMapper mapper, List<String> values) {
        try {
            return mapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private final RowMapper<QaMeta> metaMapper = (rs, rowNum) -> new QaMeta(
            rs.getString("tenant_id"),
            rs.getString("qa_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("rollout_state"),
            rs.getInt("canary_percent"),
            rs.getLong("revision"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private final RowMapper<QaEntry> entryMapper = (rs, rowNum) -> new QaEntry(
            rs.getString("tenant_id"),
            rs.getString("qa_id"),
            rs.getString("entry_id"),
            rs.getString("question"),
            fromJson(rs.getString("variants")),
            rs.getString("answer"),
            rs.getString("match_mode"),
            fromJson(rs.getString("tags")),
            rs.getInt("priority"),
            rs.getInt("enabled") == 1,
            toInstant(rs.getTimestamp("expires_at")),
            toInstant(rs.getTimestamp("last_precheck_at")),
            rs.getObject("last_precheck_ok") == null ? null : rs.getInt("last_precheck_ok") == 1,
            toInstant(rs.getTimestamp("updated_at")));

    private final RowMapper<QaPublishJob> jobMapper = (rs, rowNum) -> new QaPublishJob(
            rs.getString("publish_id"),
            rs.getString("tenant_id"),
            rs.getString("qa_id"),
            rs.getString("status"),
            rs.getObject("snapshot_revision") == null ? null : rs.getLong("snapshot_revision"),
            rs.getString("errors"),
            rs.getString("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    @Override
    public List<QaMeta> listMetas(String tenantId) {
        return jdbc.query(
                "SELECT * FROM tb_qa_meta WHERE tenant_id = ? ORDER BY qa_id",
                metaMapper,
                tenantId);
    }

    @Override
    public Optional<QaMeta> getMeta(String tenantId, String qaId) {
        List<QaMeta> rows = jdbc.query(
                "SELECT * FROM tb_qa_meta WHERE tenant_id = ? AND qa_id = ?",
                metaMapper,
                tenantId,
                qaId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void upsertMeta(QaMeta meta) {
        int updated = jdbc.update(
                """
                UPDATE tb_qa_meta SET name=?, description=?, status=?, rollout_state=?,
                    canary_percent=?, revision=?, updated_by=?, updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND qa_id=?
                """,
                meta.name(),
                meta.description(),
                meta.status(),
                meta.rolloutState(),
                meta.canaryPercent(),
                meta.revision(),
                null,
                meta.tenantId(),
                meta.qaId());
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO tb_qa_meta (tenant_id, qa_id, name, description, status, rollout_state,
                        canary_percent, revision)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,
                    meta.tenantId(),
                    meta.qaId(),
                    meta.name(),
                    meta.description(),
                    meta.status(),
                    meta.rolloutState(),
                    meta.canaryPercent(),
                    meta.revision());
        }
    }

    @Override
    public void updateMetaStatus(String tenantId, String qaId, String status, long revision) {
        jdbc.update(
                """
                UPDATE tb_qa_meta SET status=?, revision=?, updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND qa_id=?
                """,
                status,
                revision,
                tenantId,
                qaId);
    }

    @Override
    public void deleteMeta(String tenantId, String qaId) {
        jdbc.update("DELETE FROM tb_qa_entry WHERE tenant_id = ? AND qa_id = ?", tenantId, qaId);
        jdbc.update("DELETE FROM tb_qa_meta WHERE tenant_id = ? AND qa_id = ?", tenantId, qaId);
    }

    @Override
    public List<QaEntry> listEntries(String tenantId, String qaId) {
        return jdbc.query(
                "SELECT * FROM tb_qa_entry WHERE tenant_id = ? AND qa_id = ? ORDER BY priority DESC, entry_id",
                entryMapper,
                tenantId,
                qaId);
    }

    @Override
    public void replaceEntries(String tenantId, String qaId, List<QaEntry> entries) {
        jdbc.update("DELETE FROM tb_qa_entry WHERE tenant_id = ? AND qa_id = ?", tenantId, qaId);
        for (QaEntry e : entries) {
            jdbc.update(
                    """
                    INSERT INTO tb_qa_entry (tenant_id, qa_id, entry_id, question, variants, answer,
                        match_mode, tags, priority, enabled, expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    tenantId,
                    qaId,
                    e.entryId(),
                    e.question(),
                    toJson(mapper, e.variants()),
                    e.answer(),
                    e.matchMode(),
                    toJson(mapper, e.tags()),
                    e.priority(),
                    e.enabled() ? 1 : 0,
                    e.expiresAt() == null ? null : Timestamp.from(e.expiresAt()));
        }
    }

    @Override
    public List<QaEntry> entriesNeedingPrecheck(String tenantId, String qaId) {
        return jdbc.query(
                """
                SELECT * FROM tb_qa_entry
                WHERE tenant_id = ? AND qa_id = ? AND enabled = 1
                  AND (last_precheck_ok IS NULL OR last_precheck_ok = 0 OR last_precheck_at IS NULL
                       OR updated_at > last_precheck_at)
                ORDER BY priority DESC, entry_id
                """,
                entryMapper,
                tenantId,
                qaId);
    }

    @Override
    public void markPrecheckResult(String tenantId, String qaId, String entryId, boolean ok, Instant at) {
        jdbc.update(
                """
                UPDATE tb_qa_entry SET last_precheck_ok=?, last_precheck_at=?
                WHERE tenant_id=? AND qa_id=? AND entry_id=?
                """,
                ok ? 1 : 0,
                Timestamp.from(at),
                tenantId,
                qaId,
                entryId);
    }

    @Override
    public void appendHistory(String tenantId, String qaId, String entryId, String action, String actor, String snapshot) {
        jdbc.update(
                """
                INSERT INTO tb_qa_history (history_id, tenant_id, qa_id, entry_id, action, actor, snapshot)
                VALUES (?,?,?,?,?,?,?)
                """,
                UUID.randomUUID().toString(),
                tenantId,
                qaId,
                entryId,
                action,
                actor,
                snapshot);
    }

    @Override
    public void saveJob(QaPublishJob job) {
        jdbc.update(
                """
                INSERT INTO tb_qa_publish_job (publish_id, tenant_id, qa_id, status, snapshot_revision,
                    errors, created_by)
                VALUES (?,?,?,?,?,?,?)
                """,
                job.publishId(),
                job.tenantId(),
                job.qaId(),
                job.status(),
                job.snapshotRevision(),
                job.errors(),
                job.createdBy());
    }

    @Override
    public Optional<QaPublishJob> getJob(String publishId) {
        List<QaPublishJob> rows = jdbc.query(
                "SELECT * FROM tb_qa_publish_job WHERE publish_id = ?",
                jobMapper,
                publishId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<QaPublishJob> listJobs(String tenantId, String qaId) {
        return jdbc.query(
                """
                SELECT * FROM tb_qa_publish_job WHERE tenant_id = ? AND qa_id = ?
                ORDER BY created_at DESC
                """,
                jobMapper,
                tenantId,
                qaId);
    }

    @Override
    public List<QaPublishJob> listJobsInStatus(String status) {
        return jdbc.query(
                "SELECT * FROM tb_qa_publish_job WHERE status = ? ORDER BY created_at",
                jobMapper,
                status);
    }

    @Override
    public void updateJobStatus(String publishId, String status, Long snapshotRevision, String errors) {
        jdbc.update(
                """
                UPDATE tb_qa_publish_job SET status=?, snapshot_revision=COALESCE(?, snapshot_revision),
                    errors=?, updated_at=CURRENT_TIMESTAMP
                WHERE publish_id=?
                """,
                status,
                snapshotRevision,
                errors,
                publishId);
    }
}
