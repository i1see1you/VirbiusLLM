package io.virbius.control.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.EdgeTenantCredential;
import io.virbius.control.service.EdgeCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EdgeDeliveryAuthFilterTest {

    @Mock
    private EdgeCredentialService credentialService;

    @Mock
    private FilterChain filterChain;

    private EdgeDeliveryAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new EdgeDeliveryAuthFilter(credentialService, new ObjectMapper(), true);
    }

    @Test
    void missingTokenReturns401() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI())
                .thenReturn("/api/v1/edge/tenants/default/apps/beta/policy-version");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void tenantMismatchReturns403() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/edge/tenants/other/apps/beta/policy-version");
        when(request.getHeader("Authorization")).thenReturn("Bearer vrb_edge_test");
        when(credentialService.findActiveByToken("vrb_edge_test"))
                .thenReturn(Optional.of(activeCred("default")));
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(403);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void validTokenPassesFilter() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/edge/tenants/default/apps/beta/manifest");
        when(request.getHeader("Authorization")).thenReturn("Bearer vrb_edge_test");
        when(credentialService.findActiveByToken("vrb_edge_test"))
                .thenReturn(Optional.of(activeCred("default")));
        HttpServletResponse response = mock(HttpServletResponse.class);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(credentialService).touchLastUsed("cred-1");
    }

    @Test
    void extractPathTenantIdParsesUri() {
        assertEquals(
                "default",
                EdgeDeliveryAuthFilter.extractPathTenantId(
                        "/api/v1/edge/tenants/default/apps/beta/policy-version"));
    }

    private static EdgeTenantCredential activeCred(String tenantId) {
        return new EdgeTenantCredential(
                "cred-1", tenantId, "hash", "vrb_edge_tes", EdgeTenantCredential.STATUS_ACTIVE, Instant.now(), null, null);
    }
}
