package dev.yuanscaffold.platform.generation;

import dev.yuanscaffold.platform.generation.WorkflowDraftValidationService.ValidationException;
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
@RequestMapping("/api/v1/workflow-drafts/validate")
public class WorkflowDraftValidationController {
    private final WorkflowDraftValidationService service;

    public WorkflowDraftValidationController(WorkflowDraftValidationService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('studio:project:write')")
    public JsonNode validate(@Valid @RequestBody ValidationRequest request) {
        return service.validate(request.graph());
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> validationError(ValidationException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), Instant.now()));
    }

    public record ValidationRequest(@NotNull JsonNode graph) { }
}
