package io.virbius.control.repository;

import io.virbius.control.domain.EdgeTenantCredential;
import java.util.List;
import java.util.Optional;

public interface EdgeTenantCredentialRepository {

    Optional<EdgeTenantCredential> findActiveByKeyHash(String keyHash);

    List<EdgeTenantCredential> listByTenant(String tenantId);

    void insert(EdgeTenantCredential credential);

    void revoke(String tenantId, String credentialId);

    void touchLastUsed(String credentialId);
}
