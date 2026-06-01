package io.virbius.control.config;

import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.domain.enums.AccessListDimension;
import io.virbius.control.domain.enums.AccessListPolarity;
import io.virbius.control.repository.AccessListRepository;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.control.service.AccessListService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Migrate legacy flat access_list rows into named lists on first boot. */
@Component
public class NamedListBootstrap {

    private static final Logger log = LoggerFactory.getLogger(NamedListBootstrap.class);

    private final AccessListRepository legacyRepo;
    private final ListMetaRepository listMetaRepo;
    private final AccessListService accessListService;

    public NamedListBootstrap(
            AccessListRepository legacyRepo, ListMetaRepository listMetaRepo, AccessListService accessListService) {
        this.legacyRepo = legacyRepo;
        this.listMetaRepo = listMetaRepo;
        this.accessListService = accessListService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            migrateTenant("default");
            accessListService.refreshArtifacts("default");
            log.info("named list bootstrap completed for tenant=default");
        } catch (Exception e) {
            log.warn("named list bootstrap skipped: {}", e.getMessage());
        }
    }

    private void migrateTenant(String tenantId) {
        if (!listMetaRepo.listMeta(tenantId).isEmpty()) {
            return;
        }
        for (AccessListPolarity polarity : AccessListPolarity.values()) {
            for (AccessListDimension dimension : AccessListDimension.values()) {
                String listName = AccessListService.legacyListName(polarity, dimension);
                List<String> entries = legacyRepo.list(tenantId, polarity, dimension);
                if (entries.isEmpty()) {
                    continue;
                }
                listMetaRepo.upsertMeta(new AccessListMeta(tenantId, listName, dimension.value(), "migrated"));
                listMetaRepo.replaceEntries(
                        tenantId,
                        listName,
                        entries.stream().map(v -> new AccessListEntry(v, null, null, null)).toList());
            }
        }
    }
}
