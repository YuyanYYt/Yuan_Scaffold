package dev.yuanscaffold.platform.generation;

import dev.yuanscaffold.platform.generation.GeneratorPreviewService.PreviewException;
import dev.yuanscaffold.platform.web.ApiError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/code-previews")
public class CodePreviewController {
    private final GeneratorPreviewService service;

    public CodePreviewController(GeneratorPreviewService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('studio:project:write')")
    public JsonNode preview(@Valid @RequestBody PreviewRequest request) {
        return service.preview(request.manifest());
    }

    @ExceptionHandler(PreviewException.class)
    ResponseEntity<ApiError> previewError(PreviewException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), Instant.now()));
    }

    public record PreviewRequest(@NotNull JsonNode manifest) { }
}
