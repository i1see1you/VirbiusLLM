package io.virbius.control.service;

import io.virbius.control.domain.BundleVersion;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.dto.response.BundleResponseMapper;
import io.virbius.control.domain.dto.response.RuleResponseMapper;
import io.virbius.control.repository.RegistryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BundleService {

    private final RegistryRepository store;

    public BundleService(RegistryRepository store) {
        this.store = store;
    }

    public List<Map<String, Object>> listBundles(String tenantId) {
        return store.listBundles(tenantId).stream()
                .map(BundleResponseMapper::toSummary)
                .toList();
    }

    public Map<String, Object> createBundle(String tenantId, String bundleId) {
        BundleVersion b = store.createBundle(tenantId, bundleId);
        return BundleResponseMapper.toSummary(b);
    }

    public Map<String, Object> getBundle(String tenantId, String bundleId) {
        BundleVersion b = store.listBundles(tenantId).stream()
                .filter(x -> x.bundleId().equals(bundleId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("bundle not found: " + bundleId));
        return bundleDetail(tenantId, b);
    }

    public Map<String, Object> getBundleVersion(String tenantId, String bundleId, String version) {
        BundleVersion b = store.getBundle(tenantId, bundleId, version)
                .orElseThrow(() -> new IllegalArgumentException("bundle not found"));
        return bundleDetail(tenantId, b);
    }

    private Map<String, Object> bundleDetail(String tenantId, BundleVersion b) {
        Map<String, Object> detail = new LinkedHashMap<>(BundleResponseMapper.toSummary(b));
        detail.put("version", b.version());
        detail.put("metadata", b.metadata() != null ? b.metadata() : Map.of());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (RuleRevision r : store.listCurrentRules(tenantId, null)) {
            if (b.bundleId().equals(r.bundleId())) {
                rules.add(RuleResponseMapper.toSummary(r));
            }
        }
        detail.put("rules", rules);
        return detail;
    }
}