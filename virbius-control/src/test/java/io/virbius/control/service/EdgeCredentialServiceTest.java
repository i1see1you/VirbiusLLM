package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.control.common.exception.ResourceNotFoundException;
import io.virbius.control.domain.EdgeTenantCredential;
import io.virbius.control.repository.EdgeTenantCredentialRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EdgeCredentialServiceTest {

    @Mock
    private EdgeTenantCredentialRepository repository;

    @Test
    void issueStoresHashNotPlaintext() {
        EdgeCredentialService service = new EdgeCredentialService(repository);
        var issued = service.issue("default");
        assertNotNull(issued.get("api_key"));
        String apiKey = (String) issued.get("api_key");
        assertTrue(apiKey.startsWith("vrb_edge_"));

        ArgumentCaptor<EdgeTenantCredential> captor = ArgumentCaptor.forClass(EdgeTenantCredential.class);
        verify(repository).insert(captor.capture());
        EdgeTenantCredential stored = captor.getValue();
        assertEquals(EdgeCredentialHasher.sha256Hex(apiKey), stored.keyHash());
        assertEquals("default", stored.tenantId());
        assertEquals(EdgeTenantCredential.STATUS_ACTIVE, stored.status());
    }

    @Test
    void findActiveByTokenMatchesHash() {
        EdgeCredentialService service = new EdgeCredentialService(repository);
        String apiKey = "vrb_edge_test_key";
        String hash = EdgeCredentialHasher.sha256Hex(apiKey);
        EdgeTenantCredential cred = new EdgeTenantCredential(
                "id-1", "default", hash, "vrb_edge_tes", EdgeTenantCredential.STATUS_ACTIVE, Instant.now(), null, null);
        when(repository.findActiveByKeyHash(hash)).thenReturn(Optional.of(cred));

        assertTrue(service.findActiveByToken(apiKey).isPresent());
        assertTrue(service.findActiveByToken("Bearer " + apiKey).isEmpty());
    }

    @Test
    void revokeMissingCredentialThrows() {
        EdgeCredentialService service = new EdgeCredentialService(repository);
        when(repository.listByTenant("default")).thenReturn(List.of());
        assertThrows(ResourceNotFoundException.class, () -> service.revoke("default", "missing"));
    }
}
