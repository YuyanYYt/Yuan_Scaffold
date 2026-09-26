package dev.yuanscaffold.platform.generation;

import dev.yuanscaffold.platform.generation.CodeExportService.ExportException;
import dev.yuanscaffold.platform.generation.CodeExportAuditService.Outcome;
import dev.yuanscaffold.platform.generation.GeneratorPreviewService.PreviewException;
import dev.yuanscaffold.platform.web.ApiError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/code-exports")
public class CodeExportController {
    private final CodeExportService service;
    private final CodeExportAuditService audit;

    public CodeExportController(CodeExportService service, CodeExportAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('studio:project:write')")
    public ResponseEntity<byte[]> export(@Valid @RequestBody ExportRequest request) {
        UUID exportId = UUID.randomUUID();
        String manifestSha256 = audit.manifestSha256(request.manifest());
        byte[] archive;
        try {
            archive = service.export(request.manifest());
        } catch (PreviewException exception) {
            Outcome outcome = exception.status() < 500 ? Outcome.REJECTED : Outcome.FAILED;
            audit.record(exportId, manifestSha256, outcome, exception.code());
            throw exception;
        } catch (ExportException exception) {
            audit.record(exportId, manifestSha256, Outcome.FAILED, "EXPORT_FAILED");
            throw exception;
        } catch (RuntimeException exception) {
            audit.record(exportId, manifestSha256, Outcome.FAILED, "UNEXPECTED_ERROR");
            throw exception;
        }
        audit.record(exportId, manifestSha256, Outcome.SUCCESS, null);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"yuan-source-draft.zip\"")
                .header("X-Yuan-Source-Status", "DRAFT_UNVERIFIED")
                .header("X-Yuan-Export-Audit-Id", exportId.toString())
                .body(archive);
    }

    @ExceptionHandler(PreviewException.class)
    ResponseEntity<ApiError> previewError(PreviewException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(ExportException.class)
    ResponseEntity<ApiError> exportError(ExportException exception) {
        return ResponseEntity.status(503)
                .body(new ApiError("EXPORT_FAILED", exception.getMessage(), Instant.now()));
    }

    public record ExportRequest(@NotNull JsonNode manifest) { }
}
