package io.virbius.control.policy;

import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.CumulativeDef;
import io.virbius.control.repository.CumulativeRepository;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.policy.CumulativeWindow;
import io.virbius.policy.ValueSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** List / cumulative definitions for engine script reload payload. */
@Component
public class PolicyDataBuilder {

    private final ListMetaRepository listMetaRepo;
    private final CumulativeRepository cumulativeRepo;

    public PolicyDataBuilder(ListMetaRepository listMetaRepo, CumulativeRepository cumulativeRepo) {
        this.listMetaRepo = listMetaRepo;
        this.cumulativeRepo = cumulativeRepo;
    }

    public List<Map<String, Object>> buildEngineLists(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (AccessListMeta meta : listMetaRepo.listMeta(tenantId)) {
            List<String> entries = listMetaRepo.listEntries(tenantId, meta.listName()).stream()
                    .map(AccessListEntry::value)
                    .toList();
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("list_name", meta.listName());
            block.put("dimension", meta.dimension());
            block.put("entries", entries);
            blocks.add(block);
        }
        return blocks;
    }

    public List<Map<String, Object>> buildEngineCumulatives(String tenantId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (CumulativeDef def : cumulativeRepo.list(tenantId, "active")) {
            int wMin = CumulativeWindow.windowMinutes(def.windowKind(), def.windowMinutes(), def.windowHours());
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("cumulative_name", def.cumulativeName());
            block.put("dimension", def.dimension());
            block.put("window_kind", def.windowKind());
            block.put("window_minutes", wMin);
            block.put("timezone", def.timezone() != null ? def.timezone() : "UTC");
            blocks.add(block);
        }
        return blocks;
    }

    public static Map<String, Object> valueSourceMap(ValueSource vs) {
        if (vs == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        String kind =
                switch (vs.kind()) {
                    case REQUEST_FIELD -> "request_field";
                    case VAR -> "var";
                    case HEADER -> "header";
                    case QUERY -> "query";
                    case CONTENT -> "content";
                    case LITERAL -> "literal";
                    default -> "default";
                };
        m.put("kind", kind);
        if (vs.ref() != null) {
            m.put("ref", vs.ref());
        }
        if (vs.literalValue() != null) {
            m.put("value", vs.literalValue());
        }
        return m;
    }
}
