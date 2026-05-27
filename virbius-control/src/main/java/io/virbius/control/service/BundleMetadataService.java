package io.virbius.control.service;

import io.virbius.control.domain.BundleVersion;
import io.virbius.control.domain.ContextVarBinding;
import io.virbius.control.repository.RegistryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BundleMetadataService {

    private final RegistryRepository store;
    private final AccessListService accessListService;

    public BundleMetadataService(RegistryRepository store, AccessListService accessListService) {
        this.store = store;
        this.accessListService = accessListService;
    }

    public Map<String, Object> getMetadata(String tenantId, String bundleId, String version) {
        BundleVersion bundle = requireBundle(tenantId, bundleId, version);
        Map<String, Object> metadata = bundle.metadata() != null ? bundle.metadata() : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        out.put("bundle_id", bundleId);
        out.put("version", version);
        out.put("status", bundle.status());
        out.put("metadata", metadata);
        out.put("context_bindings", ContextBindingsHelper.bindingsBlock(metadata));
        out.put("context_vars", ContextBindingsHelper.parseBindings(metadata).stream()
                .map(this::varToMap)
                .toList());
        return out;
    }

    public Map<String, Object> updateContextBindings(
            String tenantId, String bundleId, String version, List<ContextVarBinding> bindings, boolean syncArtifacts) {
        BundleVersion bundle = requireBundle(tenantId, bundleId, version);
        Map<String, Object> metadata = new LinkedHashMap<>(
                bundle.metadata() != null ? bundle.metadata() : Map.of());
        metadata.put("context_bindings", ContextBindingsHelper.toMetadataBlock(bindings));
        store.updateBundleMetadata(tenantId, bundleId, version, metadata);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", true);
        out.put("context_bindings", ContextBindingsHelper.bindingsBlock(metadata));
        out.put("context_vars", bindings.stream().map(this::varToMap).toList());
        if (syncArtifacts) {
            out.put("sync", accessListService.syncRules(tenantId));
        }
        return out;
    }

    public Map<String, Object> syncGatewayArtifacts(String tenantId) {
        return accessListService.syncRules(tenantId);
    }

    public static List<ContextVarBinding> parseRequest(io.virbius.control.domain.dto.request.ContextBindingsRequest body) {
        if (body == null || body.vars() == null) {
            return List.of();
        }
        List<ContextVarBinding> out = new ArrayList<>();
        for (Map<String, String> row : body.vars()) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            String logical = row.get("logical");
            String from = row.get("from");
            String name = row.get("name");
            String field = row.get("field");
            if (from == null || from.isBlank()) {
                from = ContextVarBinding.FROM_QUERY;
            }
            out.add(new ContextVarBinding(logical, from, name, field));
        }
        return List.copyOf(out);
    }

    private BundleVersion requireBundle(String tenantId, String bundleId, String version) {
        return store.getBundle(tenantId, bundleId, version)
                .orElseThrow(() -> new IllegalArgumentException("bundle not found"));
    }

    private Map<String, Object> varToMap(ContextVarBinding b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logical", b.logical());
        m.put("from", b.from());
        if (b.name() != null && !b.name().isBlank()) {
            m.put("name", b.name());
        }
        if (b.field() != null && !b.field().isBlank()) {
            m.put("field", b.field());
        }
        return m;
    }
}
