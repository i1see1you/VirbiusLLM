package io.virbius.control.repository;

import io.virbius.control.domain.QaEntry;
import io.virbius.control.domain.QaMeta;
import io.virbius.control.domain.QaPublishJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface QaRepository {

    List<QaMeta> listMetas(String tenantId);

    Optional<QaMeta> getMeta(String tenantId, String qaId);

    void upsertMeta(QaMeta meta);

    void updateMetaStatus(String tenantId, String qaId, String status, long revision);

    void deleteMeta(String tenantId, String qaId);

    List<QaEntry> listEntries(String tenantId, String qaId);

    void replaceEntries(String tenantId, String qaId, List<QaEntry> entries);

    List<QaEntry> entriesNeedingPrecheck(String tenantId, String qaId);

    void markPrecheckResult(String tenantId, String qaId, String entryId, boolean ok, Instant at);

    void appendHistory(String tenantId, String qaId, String entryId, String action, String actor, String snapshot);

    void saveJob(QaPublishJob job);

    Optional<QaPublishJob> getJob(String publishId);

    List<QaPublishJob> listJobs(String tenantId, String qaId);

    List<QaPublishJob> listJobsInStatus(String status);

    void updateJobStatus(String publishId, String status, Long snapshotRevision, String errors);
}
