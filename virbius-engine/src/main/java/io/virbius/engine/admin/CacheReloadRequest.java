package io.virbius.engine.admin;

import io.virbius.engine.cache.PolicyDataCache;
import io.virbius.engine.cache.RuleEntry;
import java.util.List;

public record CacheReloadRequest(
        String policyVersion,
        List<RuleEntry> rules,
        List<PolicyDataCache.ListBlock> lists,
        List<PolicyDataCache.CumulativeBlock> cumulatives) {}
