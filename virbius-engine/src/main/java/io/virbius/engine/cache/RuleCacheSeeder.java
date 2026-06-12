package io.virbius.engine.cache;

import io.virbius.engine.persist.RuleCachePersistence;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RuleCacheSeeder {

    private static final Logger log = LoggerFactory.getLogger(RuleCacheSeeder.class);

    private final RuleCache cache;
    private final RuleCachePersistence persistence;

    public RuleCacheSeeder(RuleCache cache, RuleCachePersistence persistence) {
        this.cache = cache;
        this.persistence = persistence;
    }

    @PostConstruct
    public void load() {
        if (cache.ruleCount() == 0) {
            List<RuleEntry> entries = persistence.loadAll();
            if (!entries.isEmpty()) {
                cache.replaceAll("recovered", entries);
                log.info("recovered {} rules from tb_rule_cache_entry", entries.size());
            } else {
                log.warn("RuleCache is empty — run control publish: POST /api/v1/tenants/default/bundles/poc-default/versions/0.1.0/publish");
            }
        }
    }
}
