package io.virbius.control.audit;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.StreamEntryID;

@Repository
public class AuditIngestCheckpointRepository {

    private final JdbcTemplate jdbc;

    public AuditIngestCheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StreamEntryID> load(String streamKey) {
        return jdbc.query(
                        """
                        SELECT last_entry_id FROM tb_audit_ingest_checkpoint
                        WHERE stream_key = ?
                        """,
                        (rs, rowNum) -> parseId(rs.getString("last_entry_id")),
                        streamKey)
                .stream()
                .findFirst()
                .flatMap(id -> id);
    }

    public void save(String streamKey, StreamEntryID entryId) {
        if (entryId == null) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO tb_audit_ingest_checkpoint (stream_key, last_entry_id, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(stream_key) DO UPDATE SET
                  last_entry_id = excluded.last_entry_id,
                  updated_at = CURRENT_TIMESTAMP
                """,
                streamKey,
                entryId.toString());
    }

    public Optional<String> loadRaw(String streamKey) {
        return jdbc.query(
                        """
                        SELECT last_entry_id FROM tb_audit_ingest_checkpoint
                        WHERE stream_key = ?
                        """,
                        (rs, rowNum) -> rs.getString("last_entry_id"),
                        streamKey)
                .stream()
                .findFirst();
    }

    private static Optional<StreamEntryID> parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StreamEntryID(raw));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
