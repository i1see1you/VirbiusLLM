package io.virbius.control.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.EdgeTenantCredential;
import io.virbius.control.service.EdgeCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer auth for Edge SDK pull API ({@code /api/v1/edge/**}). Returns plain JSON errors (no ApiResult).
 */
@Component
public class EdgeDeliveryAuthFilter extends OncePerRequestFilter {

    private static final Pattern TENANT_PATH =
            Pattern.compile("^/api/v1/edge/tenants/([^/]+)/apps/[^/]+/.*");

    private final EdgeCredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final boolean authEnabled;

    public EdgeDeliveryAuthFilter(
            EdgeCredentialService credentialService,
            ObjectMapper objectMapper,
            @Value("${virbius.edge.delivery.auth.enabled:false}") boolean authEnabled) {
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        this.authEnabled = authEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!authEnabled) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/v1/edge/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String pathTenantId = extractPathTenantId(request.getRequestURI());
        if (pathTenantId == null) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "invalid edge path");
            return;
        }

        String rawToken = extractToken(request);
        if (rawToken.isBlank()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "missing edge api key");
            return;
        }

        Optional<EdgeTenantCredential> credential = credentialService.findActiveByToken(rawToken);
        if (credential.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "invalid edge api key");
            return;
        }
        if (!credential.get().tenantId().equals(pathTenantId)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "tenant scope mismatch");
            return;
        }

        credentialService.touchLastUsed(credential.get().credentialId());
        filterChain.doFilter(request, response);
    }

    static String extractPathTenantId(String uri) {
        if (uri == null) {
            return null;
        }
        Matcher matcher = TENANT_PATH.matcher(uri);
        return matcher.matches() ? matcher.group(1) : null;
    }

    static String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        String edgeKey = request.getHeader("X-Virbius-Edge-Key");
        return edgeKey != null ? edgeKey.trim() : "";
    }

    private void writeError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
