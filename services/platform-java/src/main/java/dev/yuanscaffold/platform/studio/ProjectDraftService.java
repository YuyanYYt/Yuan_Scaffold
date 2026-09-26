package dev.yuanscaffold.platform.studio;

import dev.yuanscaffold.platform.security.AuthContext;
import dev.yuanscaffold.platform.security.TenantPrincipal;
import dev.yuanscaffold.platform.store.PlatformStore;
import dev.yuanscaffold.platform.studio.ProjectDraftStore.DraftRow;
import dev.yuanscaffold.platform.studio.ProjectDraftStore.DraftSummary;
import dev.yuanscaffold.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectDraftService {
    private static final int MAX_MANIFEST_BYTES = 65_536;

    private final ProjectDraftStore drafts;
    private final PlatformStore platform;
    private final ObjectMapper mapper;

    public ProjectDraftService(ProjectDraftStore drafts, PlatformStore platform, ObjectMapper mapper) {
        this.drafts = drafts;
        this.platform = platform;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<DraftSummary> list() {
        return drafts.list(AuthContext.current().tenantId());
    }

    @Transactional(readOnly = true)
    public DraftView get(UUID id) {
        return view(drafts.find(AuthContext.current().tenantId(), id)
                .orElseThrow(() -> ApiException.notFound("Project draft")));
    }

    @Transactional
    public DraftView create(ProjectDraftController.SaveDraft request) {
        TenantPrincipal actor = AuthContext.current();
        String manifestJson = validateAndSerialize(request.manifest());
        UUID id = UUID.randomUUID();
        drafts.insert(actor.tenantId(), id, request.name().strip(), manifestJson, actor.userId());
        platform.insertAudit(actor.tenantId(), actor.userId(), "PROJECT_DRAFT_CREATED", "PROJECT_DRAFT", id);
        return view(drafts.find(actor.tenantId(), id).orElseThrow());
    }

    @Transactional
    public DraftView update(UUID id, ProjectDraftController.UpdateDraft request) {
        TenantPrincipal actor = AuthContext.current();
        String manifestJson = validateAndSerialize(request.manifest());
        int updated = drafts.update(actor.tenantId(), id, request.expectedRevision(),
                request.name().strip(), manifestJson, actor.userId());
        if (updated == 0) {
            if (drafts.find(actor.tenantId(), id).isEmpty()) {
                throw ApiException.notFound("Project draft");
            }
            throw ApiException.conflict("Project draft revision is stale");
        }
        platform.insertAudit(actor.tenantId(), actor.userId(), "PROJECT_DRAFT_UPDATED", "PROJECT_DRAFT", id);
        return view(drafts.find(actor.tenantId(), id).orElseThrow());
    }

    private String validateAndSerialize(JsonNode manifest) {
        if (manifest == null || !manifest.isObject()) {
            throw ApiException.invalid("manifest must be a JSON object");
        }
        JsonNode version = manifest.get("schemaVersion");
        if (version == null || !version.isTextual() || !"1.0.0".equals(version.asText())) {
            throw ApiException.invalid("manifest.schemaVersion must be 1.0.0");
        }
        // Saving a partial draft is allowed, but only fields from this versioned
        // manifest contract may be persisted. Credentials belong in a server-side
        // secret store and must never become tenant-readable project JSON.
        expectObject(manifest, Set.of("schemaVersion", "generatorVersion", "project", "entity"), "manifest");
        expectText(manifest, "generatorVersion", "manifest");
        JsonNode project = manifest.get("project");
        if (project != null) {
            expectObject(project, Set.of("groupId", "artifactId", "packageName"), "manifest.project");
            for (String field : List.of("groupId", "artifactId", "packageName")) {
                expectText(project, field, "manifest.project");
            }
        }
        JsonNode entity = manifest.get("entity");
        if (entity != null) {
            expectObject(entity, Set.of("name", "table", "apiPath", "fields", "permissions"), "manifest.entity");
            for (String field : List.of("name", "table", "apiPath")) {
                expectText(entity, field, "manifest.entity");
            }
            JsonNode fields = entity.get("fields");
            if (fields != null) {
                if (!fields.isArray() || fields.size() > 32) {
                    throw ApiException.invalid("manifest.entity.fields must be an array of at most 32 entries");
                }
                for (int index = 0; index < fields.size(); index++) {
                    JsonNode field = fields.get(index);
                    String path = "manifest.entity.fields[" + index + "]";
                    expectObject(field, Set.of("name", "type", "required", "maxLength", "precision", "scale", "minimum", "unique"), path);
                    expectText(field, "name", path);
                    expectText(field, "type", path);
                    for (String key : List.of("required", "unique")) {
                        JsonNode value = field.get(key);
                        if (value != null && !value.isBoolean()) throw ApiException.invalid(path + "." + key + " must be boolean");
                    }
                    for (String key : List.of("maxLength", "precision", "scale", "minimum")) {
                        JsonNode value = field.get(key);
                        if (value != null && !value.isIntegralNumber()) throw ApiException.invalid(path + "." + key + " must be integer");
                    }
                }
            }
            JsonNode permissions = entity.get("permissions");
            if (permissions != null) {
                expectObject(permissions, Set.of("read", "write"), "manifest.entity.permissions");
                expectText(permissions, "read", "manifest.entity.permissions");
                expectText(permissions, "write", "manifest.entity.permissions");
            }
        }
        String json = mapper.writeValueAsString(manifest);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) {
            throw ApiException.invalid("manifest exceeds 65536 UTF-8 bytes");
        }
        return json;
    }

    private static void expectObject(JsonNode value, Set<String> allowed, String path) {
        if (value == null || !value.isObject()) throw ApiException.invalid(path + " must be an object");
        for (String name : value.propertyNames()) {
            if (!allowed.contains(name)) throw ApiException.invalid(path + "." + name + " is not supported");
        }
    }

    private static void expectText(JsonNode parent, String key, String path) {
        JsonNode value = parent.get(key);
        if (value != null && !value.isTextual()) throw ApiException.invalid(path + "." + key + " must be text");
    }

    private DraftView view(DraftRow row) {
        return new DraftView(row.id(), row.name(), mapper.readTree(row.manifestJson()),
                row.revision(), row.createdAt(), row.updatedAt());
    }

    public record DraftView(UUID id, String name, JsonNode manifest, long revision,
                            Instant createdAt, Instant updatedAt) { }
}
