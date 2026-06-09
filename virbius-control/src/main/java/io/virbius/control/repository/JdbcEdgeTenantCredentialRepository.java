package io.virbius.control.repository;

import io.virbius.control.domain.EdgeTenantCredential;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEdgeTenantCredentialRepository implements EdgeTenantCredentialRepository {

    private static final RowMapper<EdgeTenantCredential> MAPPER = JdbcEdgeTenantCredentialRepository::mapRow;

    private final JdbcTemplate jdbc;

    public JdbcEdgeTenantCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static EdgeTenantCredential mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new EdgeTenantCredential(
                rs.getString("credential_id"),
                rs.getString("tenant_id"),
                rs.getString("key_hash"),
                rs.getString("key_prefix"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                timestampToInstant(rs.getTimestamp("revoked_at")),
                timestampToInstant(rs.getTimestamp("last_used_at")));
    }

    private static Instant timestampToInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public Optional<EdgeTenantCredential> findActiveByKeyHash(String keyHash) {
        List<EdgeTenantCredential> rows = jdbc.query(
                """
                SELECT credential_id, tenant_id, key_hash, key_prefix, status,
                       created_at, revoked_at, last_used_at
                FROM tb_edge_tenant_credential
                WHERE key_hash = ? AND status = ?
                """,
                MAPPER,
                keyHash,
                EdgeTenantCredential.STATUS_ACTIVE);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<EdgeTenantCredential> listByTenant(String tenantId) {
        return jdbc.query(
                """
                SELECT credential_id, tenant_id, key_hash, key_prefix, status,
                       created_at, revoked_at, last_used_at
                FROM tb_edge_tenant_credential
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                """,
                MAPPER,
                tenantId);
    }

    @Override
    public void insert(EdgeTenantCredential credential) {
        jdbc.update(
                """
                INSERT INTO tb_edge_tenant_credential
                    (credential_id, tenant_id, key_hash, key_prefix, status, created_at, revoked_at, last_used_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                credential.credentialId(),
                credential.tenantId(),
                credential.keyHash(),
                credential.keyPrefix(),
                credential.status(),
                Timestamp.from(credential.createdAt()),
                credential.revokedAt() != null ? Timestamp.from(credential.revokedAt()) : null,
                credential.lastUsedAt() != null ? Timestamp.from(credential.lastUsedAt()) : null);
    }

    @Override
    public void revoke(String tenantId, String credentialId) {
        jdbc.update(
                """
                UPDATE tb_edge_tenant_credential
                SET status = ?, revoked_at = ?
                WHERE tenant_id = ? AND credential_id = ? AND status = ?
                """,
                EdgeTenantCredential.STATUS_REVOKED,
                Timestamp.from(Instant.now()),
                tenantId,
                credentialId,
                EdgeTenantCredential.STATUS_ACTIVE);
    }

    @Override
    public void touchLastUsed(String credentialId) {
        jdbc.update(
                """
                UPDATE tb_edge_tenant_credential
                SET last_used_at = ?
                WHERE credential_id = ?
                """,
                Timestamp.from(Instant.now()),
                credentialId);
    }
}
