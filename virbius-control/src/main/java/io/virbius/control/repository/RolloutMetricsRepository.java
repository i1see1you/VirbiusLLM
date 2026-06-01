package io.virbius.control.repository;

public interface RolloutMetricsRepository {

    long countReview24h(String tenantId, String ruleId);

    long countTotalRequests24h(String tenantId);

    long countBlockInCanary24h(String tenantId, String ruleId);
}
