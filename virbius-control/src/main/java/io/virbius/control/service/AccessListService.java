package io.virbius.control.service;

import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.dto.request.AccessListEntryInput;
import io.virbius.control.domain.enums.AccessListDimension;
import io.virbius.control.domain.enums.AccessListPolarity;
import io.virbius.control.repository.AccessListRepository;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.control.repository.RegistryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AccessListService {

    private final AccessListRepository legacyListRepo;
    private final ListMetaRepository listMetaRepo;
    private final RegistryRepository registryRepo;
    private final PublishService publishService;
    private final ArtifactService artifactService;

    public AccessListService(
            AccessListRepository legacyListRepo,
            ListMetaRepository listMetaRepo,
            RegistryRepository registryRepo,
            PublishService publishService,
            ArtifactService artifactService) {
        this.legacyListRepo = legacyListRepo;
        this.listMetaRepo = listMetaRepo;
        this.registryRepo = registryRepo;
        this.publishService = publishService;
        this.artifactService = artifactService;
    }

    public Map<String, Object> getAll(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        List<Map<String, Object>> lists = new ArrayList<>();
        for (AccessListMeta meta : listMetaRepo.listMeta(tenantId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("list_name", meta.listName());
            item.put("dimension", meta.dimension());
            item.put("remark", meta.remark());
            item.put("entries", entryMaps(listMetaRepo.listEntries(tenantId, meta.listName())));
            lists.add(item);
        }
        out.put("lists", lists);
        return out;
    }

    public List<AccessListEntry> getEntries(String tenantId, String listName) {
        listMetaRepo.getMeta(tenantId, listName).orElseThrow(() -> new IllegalArgumentException("list not found"));
        return listMetaRepo.listEntries(tenantId, listName);
    }

    /** Legacy shim: maps polarity+dimension to historical list_name `{polarity}_{dimension}`. */
    public List<String> get(String tenantId, AccessListPolarity polarity, AccessListDimension dimension) {
        String listName = legacyListName(polarity, dimension);
        if (listMetaRepo.getMeta(tenantId, listName).isPresent()) {
            return listMetaRepo.listEntries(tenantId, listName).stream().map(AccessListEntry::value).toList();
        }
        return legacyListRepo.list(tenantId, polarity, dimension);
    }

    public Map<String, Object> replaceAndPush(
            String tenantId, AccessListPolarity polarity, AccessListDimension dimension, List<String> values) {
        String listName = legacyListName(polarity, dimension);
        ensureLegacyMeta(tenantId, listName, dimension);
        listMetaRepo.replaceEntries(tenantId, listName, toValueOnlyEntries(normalizeValues(tenantId, dimension, values)));
        return refreshArtifactsAndPush(tenantId, Map.of());
    }

    public Map<String, Object> addEntriesAndPush(
            String tenantId, AccessListPolarity polarity, AccessListDimension dimension, List<String> values) {
        String listName = legacyListName(polarity, dimension);
        ensureLegacyMeta(tenantId, listName, dimension);
        int added = 0;
        for (String v : normalizeValues(tenantId, dimension, values)) {
            if (listMetaRepo.addEntry(tenantId, listName, v, null, null)) {
                added++;
            }
        }
        return refreshArtifactsAndPush(tenantId, Map.of("added", added));
    }

    public Map<String, Object> removeEntryAndPush(
            String tenantId, AccessListPolarity polarity, AccessListDimension dimension, String value) {
        String listName = legacyListName(polarity, dimension);
        boolean removed = listMetaRepo.removeEntry(tenantId, listName, value);
        return refreshArtifactsAndPush(tenantId, Map.of("removed", removed));
    }

    public Map<String, Object> addEntry(
            String tenantId, String listName, String value, String remark, Instant expiresAt) {
        listMetaRepo.getMeta(tenantId, listName).orElseThrow(() -> new IllegalArgumentException("list not found"));
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("value required");
        }
        listMetaRepo.addEntry(tenantId, listName, normalized, remark, expiresAt);
        return refreshArtifactsAndPush(tenantId, Map.of("added", true));
    }

    public Map<String, Object> replaceNamedEntries(String tenantId, String listName, List<AccessListEntryInput> inputs) {
        AccessListMeta meta = listMetaRepo
                .getMeta(tenantId, listName)
                .orElseThrow(() -> new IllegalArgumentException("list not found"));
        List<AccessListEntry> entries = new ArrayList<>();
        if (inputs != null) {
            for (AccessListEntryInput in : inputs) {
                if (in == null || in.value() == null || in.value().isBlank()) {
                    continue;
                }
                String v = AccessListEntryValidator.normalizeAndValidate(
                        meta.dimension(), in.value().trim(), bundleMetadata(tenantId));
                entries.add(new AccessListEntry(v, in.remark(), null, in.expiresAt()));
            }
        }
        listMetaRepo.replaceEntries(tenantId, listName, entries);
        return refreshArtifacts(tenantId);
    }

    public Map<String, Object> pushToEngine(String tenantId) {
        return Map.of("engine_reload", publishService.runtimeSnapshot(tenantId));
    }

    public Map<String, Object> refreshArtifacts(String tenantId) {
        Map<String, Object> metadata = bundleMetadata(tenantId);
        Map<String, String> artifacts = artifactService.write(tenantId, metadata);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("refreshed", true);
        summary.put("artifacts", artifacts);
        return summary;
    }

    public Map<String, Object> syncRules(String tenantId) {
        return refreshArtifacts(tenantId);
    }

    public Map<String, Object> syncAndPublish(String tenantId, String bundleId, String version) {
        Map<String, Object> sync = new LinkedHashMap<>(refreshArtifacts(tenantId));
        if (registryRepo.listBundles(tenantId).isEmpty()) {
            registryRepo.createBundle(tenantId, bundleId);
        }
        try {
            sync.put("publish", publishService.publish(tenantId, bundleId, version));
        } catch (IllegalStateException alreadyActive) {
            sync.put("engine_reload", publishService.runtimeSnapshot(tenantId));
        }
        return sync;
    }

    private Map<String, Object> refreshArtifactsAndPush(String tenantId, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>(refreshArtifacts(tenantId));
        out.putAll(extra);
        out.put("engine_reload", publishService.runtimeSnapshot(tenantId));
        return out;
    }

    private void ensureLegacyMeta(String tenantId, String listName, AccessListDimension dimension) {
        if (listMetaRepo.getMeta(tenantId, listName).isEmpty()) {
            listMetaRepo.upsertMeta(new AccessListMeta(tenantId, listName, dimension.value(), null));
        }
    }

    public static String legacyListName(AccessListPolarity polarity, AccessListDimension dimension) {
        return polarity.value() + "_" + dimension.value();
    }

    private Map<String, Object> bundleMetadata(String tenantId) {
        return registryRepo
                .getBundle(tenantId, "poc-default", "0.1.0")
                .map(b -> b.metadata() != null ? b.metadata() : Map.<String, Object>of())
                .orElse(Map.of());
    }

    private List<String> normalizeValues(String tenantId, AccessListDimension dimension, List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        Map<String, Object> metadata = bundleMetadata(tenantId);
        for (String v : values) {
            out.add(AccessListEntryValidator.normalizeAndValidate(dimension, v, metadata));
        }
        return out;
    }

    private static List<AccessListEntry> toValueOnlyEntries(List<String> values) {
        List<AccessListEntry> out = new ArrayList<>();
        for (String v : values) {
            out.add(new AccessListEntry(v, null, null, null));
        }
        return out;
    }

    public static List<Map<String, Object>> entryMaps(List<AccessListEntry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AccessListEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", e.value());
            if (e.remark() != null) {
                m.put("remark", e.remark());
            }
            if (e.createdAt() != null) {
                m.put("created_at", e.createdAt().toString());
            }
            if (e.expiresAt() != null) {
                m.put("expires_at", e.expiresAt().toString());
            }
            out.add(m);
        }
        return out;
    }
}
