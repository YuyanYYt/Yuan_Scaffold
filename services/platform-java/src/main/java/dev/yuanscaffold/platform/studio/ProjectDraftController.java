package dev.yuanscaffold.platform.studio;

import dev.yuanscaffold.platform.security.AuthContext;
import dev.yuanscaffold.platform.studio.ProjectDraftService.DraftView;
import dev.yuanscaffold.platform.studio.ProjectDraftStore.DraftCursor;
import dev.yuanscaffold.platform.studio.ProjectDraftStore.DraftPage;
import dev.yuanscaffold.platform.studio.ProjectDraftStore.DraftSummary;
import dev.yuanscaffold.platform.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/project-drafts")
public class ProjectDraftController {
    private final ProjectDraftService service;
    private final ProjectDraftStore drafts;

    public ProjectDraftController(ProjectDraftService service, ProjectDraftStore drafts) {
        this.service = service;
        this.drafts = drafts;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('studio:project:read')")
    public List<DraftSummary> list() {
        return service.list();
    }

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('studio:project:read')")
    public DraftPage page(@RequestParam(defaultValue = "30") int limit,
                          @RequestParam(required = false) String beforeUpdatedAt,
                          @RequestParam(required = false) String beforeId) {
        if (limit < 1 || limit > 100) {
            throw ApiException.invalid("limit must be between 1 and 100");
        }
        if ((beforeUpdatedAt == null) != (beforeId == null)) {
            throw ApiException.invalid("beforeUpdatedAt and beforeId must be supplied together");
        }
        DraftCursor cursor = null;
        if (beforeUpdatedAt != null) {
            try {
                cursor = new DraftCursor(Instant.parse(beforeUpdatedAt), UUID.fromString(beforeId));
            } catch (DateTimeParseException | IllegalArgumentException ignored) {
                throw ApiException.invalid("before cursor is invalid");
            }
        }
        return drafts.page(AuthContext.current().tenantId(), limit, cursor);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('studio:project:read')")
    public DraftView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('studio:project:write')")
    public ResponseEntity<DraftView> create(@Valid @RequestBody SaveDraft request) {
        DraftView draft = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/project-drafts/" + draft.id())).body(draft);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('studio:project:write')")
    public DraftView update(@PathVariable UUID id, @Valid @RequestBody UpdateDraft request) {
        return service.update(id, request);
    }

    public record SaveDraft(
            @NotBlank @Size(max = 120) String name,
            @NotNull JsonNode manifest
    ) { }

    public record UpdateDraft(
            @NotBlank @Size(max = 120) String name,
            @NotNull JsonNode manifest,
            @NotNull @Positive Long expectedRevision
    ) { }
}
