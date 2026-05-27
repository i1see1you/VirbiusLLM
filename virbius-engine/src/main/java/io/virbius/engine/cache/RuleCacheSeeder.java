package io.virbius.engine.cache;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 不在此写入关键字词表。RuleCache 应由 control 发布（{@code POST .../publish}）从 {@code tb_rule_history} 加载。
 * 若缓存为空，仅打日志提示。
 */
@Component
public class RuleCacheSeeder {

    private static final Logger log = LoggerFactory.getLogger(RuleCacheSeeder.class);

    private final RuleCache cache;

    public RuleCacheSeeder(RuleCache cache) {
        this.cache = cache;
    }

    @PostConstruct
    public void warnIfEmpty() {
        if (cache.ruleCount() == 0) {
            log.warn(
                    "RuleCache is empty — run control publish: POST /api/v1/tenants/default/bundles/poc-default/versions/0.1.0/publish (see docs/POC-SEED-API.md)");
        }
    }
}
