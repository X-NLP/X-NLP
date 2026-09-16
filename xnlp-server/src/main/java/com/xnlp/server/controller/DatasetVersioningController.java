package com.xnlp.server.controller;

import com.xnlp.server.dataset.versioning.DatasetVersioningException;
import com.xnlp.server.dataset.versioning.DatasetVersioningService;
import com.xnlp.server.dto.ApiErrorResponse;
import com.xnlp.server.dto.DatasetEntryMutationRequest;
import com.xnlp.server.dto.DatasetEntryMutationResponse;
import com.xnlp.server.dto.DatasetImportRequest;
import com.xnlp.server.dto.DatasetImportResponse;
import com.xnlp.server.dto.PageResponse;
import com.xnlp.server.dto.VersionedDatasetVersionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/datasets/{datasetId}")
@Validated
public class DatasetVersioningController {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    private final DatasetVersioningService versioning;

    public DatasetVersioningController(DatasetVersioningService versioning) {
        this.versioning = versioning;
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public DatasetEntryMutationResponse createEntry(
            @PathVariable String datasetId,
            @Valid @RequestBody DatasetEntryMutationRequest request) {
        return versioning.createEntry(datasetId, request);
    }

    @PutMapping("/entries/{entryId}")
    public DatasetEntryMutationResponse replaceEntry(
            @PathVariable String datasetId,
            @PathVariable String entryId,
            @Valid @RequestBody DatasetEntryMutationRequest request) {
        return versioning.replaceEntry(datasetId, entryId, request);
    }

    @DeleteMapping("/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEntry(
            @PathVariable String datasetId,
            @PathVariable String entryId,
            @RequestParam @PositiveOrZero long expectedVersion) {
        versioning.deleteEntry(datasetId, entryId, expectedVersion);
    }

    @GetMapping("/versions")
    public PageResponse<VersionedDatasetVersionResponse> versions(
            @PathVariable String datasetId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return versioning.versions(datasetId, page, size);
    }

    @PostMapping("/imports")
    public ResponseEntity<DatasetImportResponse> importEntries(
            @PathVariable String datasetId,
            @Valid @RequestBody DatasetImportRequest request) {
        return ResponseEntity.accepted().body(versioning.importEntries(datasetId, request));
    }

    @GetMapping("/imports/{jobId}")
    public DatasetImportResponse importReport(
            @PathVariable String datasetId,
            @PathVariable String jobId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return versioning.importReport(datasetId, jobId, limit, offset);
    }

    @ExceptionHandler(DatasetVersioningException.class)
    public ResponseEntity<ApiErrorResponse> handle(
            DatasetVersioningException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.reason()) {
            case DATASET_NOT_FOUND, DATASET_ENTRY_NOT_FOUND, DATASET_IMPORT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DATASET_VERSION_CONFLICT -> HttpStatus.CONFLICT;
        };
        String code = switch (exception.reason()) {
            case DATASET_NOT_FOUND -> "dataset_not_found";
            case DATASET_ENTRY_NOT_FOUND -> "dataset_entry_not_found";
            case DATASET_VERSION_CONFLICT -> "dataset_version_conflict";
            case DATASET_IMPORT_NOT_FOUND -> "dataset_import_not_found";
        };
        Map<String, Object> detail = null;
        if (exception.currentVersion() != null) {
            detail = new LinkedHashMap<>();
            detail.put("currentVersion", exception.currentVersion());
        }
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(), status.value(), code, exception.getMessage(), detail, null, requestId, null));
    }
}
