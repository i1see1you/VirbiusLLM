package io.virbius.control.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.QaEntry;
import io.virbius.control.domain.QaMeta;
import io.virbius.control.domain.QaPublishJob;
import io.virbius.control.repository.QaRepository;
import io.virbius.control.service.EnginePrecheckClient;
import io.virbius.control.service.QaArtifactPublisher;
import io.virbius.control.service.deploy.NodeRegistryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QaPublishJobService {

    private static final Logger log = LoggerFactory.getLogger(QaPublishJobService.class);

    enum AckDecision {
        ACKED,
        PENDING,
        FAILED
    }

    private final QaRepository repo;
    private final QaArtifactPublisher publisher;
    private final EnginePrecheckClient precheckClient;
    private final NodeRegistryService nodeRegistry;
    private final ObjectMapper mapper;
    private final long ackTimeoutSeconds;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qa-publish-job");
                t.setDaemon(true);
                return t;
            });
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public QaPublishJobService(
            QaRepository repo,
            QaArtifactPublisher publisher,
            EnginePrecheckClient precheckClient,
            NodeRegistryService nodeRegistry,
            ObjectMapper mapper,
            @Value("${virbius.qa.ack-timeout-seconds:120}") long ackTimeoutSeconds) {
        this.repo = repo;
        this.publisher = publisher;
        this.precheckClient = precheckClient;
        this.nodeRegistry = nodeRegistry;
        this.mapper = mapper;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${virbius.qa.job-poll-ms:2000}")
    public void tick() {
        for (QaPublishJob job : repo.listJobsInStatus("pending")) {
            submit(job, this::advancePending);
        }
        for (QaPublishJob job : repo.listJobsInStatus("waiting_ack")) {
            submit(job, this::advanceAck);
        }
    }

    private void submit(QaPublishJob job, Consumer<QaPublishJob> step) {
        if (!inFlight.add(job.publishId())) {
            return;
        }
        executor.submit(() -> {
            try {
                step.accept(job);
            } catch (Exception e) {
                log.warn("qa publish job {} failed: {}", job.publishId(), e.getMessage());
                repo.updateJobStatus(job.publishId(), "failed", null, e.getMessage());
            } finally {
                inFlight.remove(job.publishId());
            }
        });
    }

    void advancePending(QaPublishJob job) {
        String tenantId = job.tenantId();
        String qaId = job.qaId();
        repo.updateJobStatus(job.publishId(), "checking", null, null);

        List<QaEntry> needCheck = repo.entriesNeedingPrecheck(tenantId, qaId);
        List<Map<String, Object>> errors = new ArrayList<>();
        for (QaEntry entry : needCheck) {
            Map<String, Object> error = precheckAnswer(tenantId, qaId, entry);
            if (error != null) {
                errors.add(error);
            }
        }
        if (!errors.isEmpty()) {
            repo.updateJobStatus(job.publishId(), "failed", null, json(errors));
            return;
        }

        repo.updateJobStatus(job.publishId(), "publishing", null, null);
        QaMeta meta = repo.getMeta(tenantId, qaId)
                .orElseThrow(() -> new IllegalStateException("qa library disappeared: " + qaId));
        List<QaEntry> entries = repo.listEntries(tenantId, qaId);
        long revision = publisher.publish(tenantId, meta, entries);
        repo.updateJobStatus(job.publishId(), "waiting_ack", revision, null);
        repo.appendHistory(tenantId, qaId, null, "publish", null, "revision=" + revision);
    }

    private Map<String, Object> precheckAnswer(String tenantId, String qaId, QaEntry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entry_id", entry.entryId());
        try {
            Map<String, Object> resp = precheckClient.precheck(tenantId, entry.answer());
            Object error = resp.get("error");
            boolean blocked = Boolean.TRUE.equals(resp.get("hit"));
            if (error != null && !String.valueOf(error).isBlank()) {
                repo.markPrecheckResult(tenantId, qaId, entry.entryId(), false, Instant.now());
                row.put("error", String.valueOf(error));
                return row;
            }
            if (blocked) {
                repo.markPrecheckResult(tenantId, qaId, entry.entryId(), false, Instant.now());
                row.put("error", "precheck hit: " + resp.get("category"));
                return row;
            }
            repo.markPrecheckResult(tenantId, qaId, entry.entryId(), true, Instant.now());
            return null;
        } catch (Exception ex) {
            repo.markPrecheckResult(tenantId, qaId, entry.entryId(), false, Instant.now());
            row.put("error", ex.getMessage());
            return row;
        }
    }

    void advanceAck(QaPublishJob job) {
        Long target = job.snapshotRevision();
        if (target == null) {
            repo.updateJobStatus(job.publishId(), "failed", null, "missing snapshot revision");
            return;
        }
        Set<String> online = new LinkedHashSet<>();
        for (Map<String, String> node : nodeRegistry.listNodes("gateway", job.tenantId())) {
            online.add(node.get("instance_id"));
        }
        Map<String, Map<String, String>> acks = publisher.readAcks(job.tenantId());
        AckDecision decision = evaluateAcks(online, acks, target);
        switch (decision) {
            case ACKED -> {
                repo.updateJobStatus(job.publishId(), "acked", target, null);
                repo.updateMetaStatus(job.tenantId(), job.qaId(), "enabled", target);
            }
            case FAILED -> repo.updateJobStatus(job.publishId(), "failed", target, "agent ack failed");
            case PENDING -> {
                if (job.createdAt() != null
                        && Instant.now().isAfter(job.createdAt().plusSeconds(ackTimeoutSeconds))) {
                    repo.updateJobStatus(job.publishId(), "failed", target, "ack timeout");
                }
            }
        }
    }

    static AckDecision evaluateAcks(
            Set<String> onlineNodes, Map<String, Map<String, String>> acks, long targetRevision) {
        if (onlineNodes.isEmpty()) {
            return AckDecision.ACKED;
        }
        for (String node : onlineNodes) {
            Map<String, String> ack = acks.get(node);
            if (ack == null || ack.isEmpty()) {
                return AckDecision.PENDING;
            }
            if ("failed".equalsIgnoreCase(ack.getOrDefault("status", ""))) {
                return AckDecision.FAILED;
            }
            String rawRevision = ack.get("revision");
            long revision = rawRevision == null ? -1 : parseLong(rawRevision);
            if (revision < targetRevision) {
                return AckDecision.PENDING;
            }
        }
        return AckDecision.ACKED;
    }

    private static long parseLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
