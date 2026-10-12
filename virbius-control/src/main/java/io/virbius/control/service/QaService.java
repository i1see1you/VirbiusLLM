package io.virbius.control.service;

import io.virbius.control.domain.QaEntry;
import io.virbius.control.domain.QaMeta;
import io.virbius.control.domain.QaPublishJob;
import io.virbius.control.domain.dto.request.QaEntryInput;
import io.virbius.control.domain.dto.request.QaMetaRequest;
import io.virbius.control.repository.QaRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class QaService {

    private static final Set<String> ROLLOUT_STATES = Set.of("dry_run", "canary", "full", "disabled");
    private static final Set<String> ACTIVE_JOB_STATES =
            Set.of("pending", "checking", "publishing", "waiting_ack");

    private final QaRepository repo;
    private final QaArtifactPublisher publisher;

    public QaService(QaRepository repo, QaArtifactPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    public List<QaMeta> listMetas(String tenantId) {
        return repo.listMetas(tenantId);
    }

    public QaMeta getMetaOrThrow(String tenantId, String qaId) {
        return repo.getMeta(tenantId, qaId)
                .orElseThrow(() -> new IllegalArgumentException("qa library not found: " + qaId));
    }

    public QaMeta createMeta(String tenantId, QaMetaRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        String rollout = normalizeRollout(request.rollout_state());
        String qaId = UUID.randomUUID().toString();
        QaMeta meta = new QaMeta(
                tenantId,
                qaId,
                request.name().trim(),
                request.description(),
                "draft",
                rollout,
                normalizeCanary(request.canary_percent()),
                0L,
                Instant.now(),
                Instant.now());
        repo.upsertMeta(meta);
        return meta;
    }

    public QaMeta updateMeta(String tenantId, String qaId, QaMetaRequest request) {
        QaMeta current = getMetaOrThrow(tenantId, qaId);
        String name = request != null && request.name() != null && !request.name().isBlank()
                ? request.name().trim()
                : current.name();
        String description = request != null && request.description() != null
                ? request.description()
                : current.description();
        String rollout = request != null && request.rollout_state() != null
                ? normalizeRollout(request.rollout_state())
                : current.rolloutState();
        int canary = request != null && request.canary_percent() != null
                ? normalizeCanary(request.canary_percent())
                : current.canaryPercent();
        QaMeta updated = new QaMeta(
                tenantId,
                qaId,
                name,
                description,
                current.status(),
                rollout,
                canary,
                current.revision(),
                current.createdAt(),
                Instant.now());
        repo.upsertMeta(updated);
        return updated;
    }

    public void deleteMeta(String tenantId, String qaId) {
        repo.deleteMeta(tenantId, qaId);
    }

    public List<QaEntry> listEntries(String tenantId, String qaId) {
        getMetaOrThrow(tenantId, qaId);
        return repo.listEntries(tenantId, qaId);
    }

    public List<QaEntry> replaceEntries(String tenantId, String qaId, List<QaEntryInput> inputs) {
        getMetaOrThrow(tenantId, qaId);
        List<QaEntry> entries = QaEntryValidator.validateAndNormalize(tenantId, qaId, inputs);
        repo.replaceEntries(tenantId, qaId, entries);
        repo.appendHistory(tenantId, qaId, null, "replace_entries", null, "count=" + entries.size());
        return repo.listEntries(tenantId, qaId);
    }

    public QaPublishJob submitPublish(String tenantId, String qaId) {
        getMetaOrThrow(tenantId, qaId);
        if (repo.listEntries(tenantId, qaId).stream().noneMatch(QaEntry::enabled)) {
            throw new IllegalArgumentException("no enabled entries to publish");
        }
        for (QaPublishJob job : repo.listJobs(tenantId, qaId)) {
            if (ACTIVE_JOB_STATES.contains(job.status())) {
                throw new IllegalStateException("publish already in progress: " + job.publishId());
            }
        }
        QaPublishJob job = new QaPublishJob(
                UUID.randomUUID().toString(),
                tenantId,
                qaId,
                "pending",
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
        repo.saveJob(job);
        return job;
    }

    public List<QaPublishJob> listJobs(String tenantId, String qaId) {
        getMetaOrThrow(tenantId, qaId);
        return repo.listJobs(tenantId, qaId);
    }

    public Map<String, Object> ackState(String tenantId, String qaId) {
        QaMeta meta = getMetaOrThrow(tenantId, qaId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("qa_id", qaId);
        out.put("status", meta.status());
        out.put("revision", meta.revision());
        out.put("acks", publisher.readAcks(tenantId));
        return out;
    }

    private static String normalizeRollout(String raw) {
        String value = raw == null || raw.isBlank() ? "dry_run" : raw.trim().toLowerCase();
        if (!ROLLOUT_STATES.contains(value)) {
            throw new IllegalArgumentException("rollout_state must be one of " + ROLLOUT_STATES);
        }
        return value;
    }

    private static int normalizeCanary(Integer percent) {
        int value = percent == null ? 0 : percent;
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("canary_percent must be 0..100");
        }
        return value;
    }
}
