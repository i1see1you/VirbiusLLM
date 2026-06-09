package io.virbius.control.service;

import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.EdgeTenantCredential;
import io.virbius.control.repository.EdgeTenantCredentialRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EdgeCredentialService {

    private static final String TOKEN_PREFIX = "vrb_edge_";
    private static final int RANDOM_BYTES = 24;
    private static final int KEY_PREFIX_LEN = 12;

    private final EdgeTenantCredentialRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    public EdgeCredentialService(EdgeTenantCredentialRepository repository) {
        this.repository = repository;
    }

    public Optional<EdgeTenantCredential> findActiveByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = EdgeCredentialHasher.sha256Hex(rawToken.trim());
        return repository.findActiveByKeyHash(hash);
    }

    public void touchLastUsed(String credentialId) {
        repository.touchLastUsed(credentialId);
    }

    public Map<String, Object> issue(String tenantId) {
        String apiKey = generateApiKey();
        String hash = EdgeCredentialHasher.sha256Hex(apiKey);
        String credentialId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        EdgeTenantCredential stored = new EdgeTenantCredential(
                credentialId,
                tenantId,
                hash,
                apiKey.substring(0, Math.min(KEY_PREFIX_LEN, apiKey.length())),
                EdgeTenantCredential.STATUS_ACTIVE,
                now,
                null,
                null);
        repository.insert(stored);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credential_id", credentialId);
        out.put("tenant_id", tenantId);
        out.put("api_key", apiKey);
        out.put("key_prefix", stored.keyPrefix());
        out.put("status", stored.status());
        out.put("created_at", now.toString());
        return out;
    }

    public List<Map<String, Object>> list(String tenantId) {
        return repository.listByTenant(tenantId).stream().map(this::toPublicView).toList();
    }

    public void revoke(String tenantId, String credentialId) {
        List<EdgeTenantCredential> rows = repository.listByTenant(tenantId).stream()
                .filter(c -> c.credentialId().equals(credentialId))
                .toList();
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("edge credential", credentialId);
        }
        repository.revoke(tenantId, credentialId);
    }

    private Map<String, Object> toPublicView(EdgeTenantCredential cred) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credential_id", cred.credentialId());
        out.put("tenant_id", cred.tenantId());
        out.put("key_prefix", cred.keyPrefix());
        out.put("status", cred.status());
        out.put("created_at", cred.createdAt().toString());
        if (cred.revokedAt() != null) {
            out.put("revoked_at", cred.revokedAt().toString());
        }
        if (cred.lastUsedAt() != null) {
            out.put("last_used_at", cred.lastUsedAt().toString());
        }
        return out;
    }

    private String generateApiKey() {
        byte[] random = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(random);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }
}
