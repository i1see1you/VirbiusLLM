package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.QaEntry;
import io.virbius.control.domain.QaMeta;
import io.virbius.control.domain.QaPublishJob;
import io.virbius.control.domain.dto.request.QaEntriesRequest;
import io.virbius.control.domain.dto.request.QaMetaRequest;
import io.virbius.control.service.QaService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/qa")
public class QaAdminController {

    private final QaService qaService;

    public QaAdminController(QaService qaService) {
        this.qaService = qaService;
    }

    @GetMapping
    public ApiResult<List<QaMeta>> listMetas(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(qaService.listMetas(tenantId));
    }

    @PostMapping
    public ApiResult<QaMeta> createMeta(
            @PathVariable("tenantId") String tenantId, @RequestBody QaMetaRequest request) {
        return ApiResult.ok(qaService.createMeta(tenantId, request));
    }

    @GetMapping("/{qaId}")
    public ApiResult<QaMeta> getMeta(
            @PathVariable("tenantId") String tenantId, @PathVariable("qaId") String qaId) {
        return ApiResult.ok(qaService.getMetaOrThrow(tenantId, qaId));
    }

    @PutMapping("/{qaId}")
    public ApiResult<QaMeta> updateMeta(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("qaId") String qaId,
            @RequestBody QaMetaRequest request) {
        return ApiResult.ok(qaService.updateMeta(tenantId, qaId, request));
    }

    @DeleteMapping("/{qaId}")
    public ApiResult<Void> deleteMeta(
            @PathVariable("tenantId") String tenantId, @PathVariable("qaId") String qaId) {
        qaService.deleteMeta(tenantId, qaId);
        return ApiResult.ok();
    }

    @GetMapping("/{qaId}/entries")
    public ApiResult<List<QaEntry>> listEntries(
            @PathVariable("tenantId") String tenantId, @PathVariable("qaId") String qaId) {
        return ApiResult.ok(qaService.listEntries(tenantId, qaId));
    }

    @PutMapping("/{qaId}/entries")
    public ApiResult<List<QaEntry>> replaceEntries(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("qaId") String qaId,
            @RequestBody QaEntriesRequest request) {
        return ApiResult.ok(qaService.replaceEntries(
                tenantId, qaId, request == null ? null : request.entries()));
    }

    @PostMapping("/{qaId}/publish")
    public ApiResult<QaPublishJob> publish(
            @PathVariable("tenantId") String tenantId, @PathVariable("qaId") String qaId) {
        return ApiResult.ok(qaService.submitPublish(tenantId, qaId));
    }

    @GetMapping("/{qaId}/jobs")
    public ApiResult<List<QaPublishJob>> listJobs(
            @PathVariable("tenantId") String tenantId, @PathVariable("qaId") String qaId) {
        return ApiResult.ok(qaService.listJobs(tenantId, qaId));
    }

    @GetMapping("/{qaId}/ack")
    public ApiResult<Map<String, Object>> ackState(
            @PathVariable("tenantId") String tenantId, @PathVariable("qaId") String qaId) {
        return ApiResult.ok(qaService.ackState(tenantId, qaId));
    }
}
