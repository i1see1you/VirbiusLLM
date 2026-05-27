package io.virbius.control.config;

import io.virbius.control.service.AccessListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AccessListBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AccessListBootstrap.class);

    private final AccessListService accessListService;

    public AccessListBootstrap(AccessListService accessListService) {
        this.accessListService = accessListService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncDefaultArtifacts() {
        try {
            accessListService.syncRules("default");
            log.info("access lists synced for tenant=default (gateway/edge artifacts written)");
        } catch (Exception e) {
            log.warn("access list bootstrap skipped: {}", e.getMessage());
        }
    }
}
