package io.virbius.control.service;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.domain.RolloutStateHelper;
import org.springframework.stereotype.Component;

/** Refresh gateway artifacts / engine cache when rollout enters or leaves execution plane. */
@Component
public class RuleExecutionSync {

    private final AccessListService accessListService;
    private final PublishService publishService;

    public RuleExecutionSync(AccessListService accessListService, PublishService publishService) {
        this.accessListService = accessListService;
        this.publishService = publishService;
    }

    public void afterRolloutChange(String tenantId, RuleRevision before, RuleRevision after) {
        boolean was = RolloutStateHelper.inExecutionPlane(before);
        boolean now = RolloutStateHelper.inExecutionPlane(after);
        if (was || now) {
            accessListService.refreshArtifacts(tenantId, "rollout");
            publishService.runtimeSnapshot(tenantId);
        }
    }

    public void afterContentChange(String tenantId, RuleRevision rule) {
        if (RolloutStateHelper.inExecutionPlane(rule)) {
            accessListService.refreshArtifacts(tenantId, "rollout");
            publishService.runtimeSnapshot(tenantId);
        }
    }
}
