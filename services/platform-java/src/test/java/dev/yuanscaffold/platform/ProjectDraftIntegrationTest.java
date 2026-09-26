package dev.yuanscaffold.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.yuanscaffold.platform.security.Permission;
import dev.yuanscaffold.platform.store.PlatformStore;
import dev.yuanscaffold.platform.studio.ProjectDraftStore;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-drafts;DB_CLOSE_DELAY=-1",
        "platform.bootstrap.tenant-slug=",
        "platform.bootstrap.username=",
        "platform.bootstrap.password="
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectDraftIntegrationTest {
    private static final String ADMIN_PASSWORD = "SyntheticAdminPassword123";
    private static final String READER_PASSWORD = "SyntheticReaderPassword123";
    private static final String MANIFEST = """
            {"schemaVersion":"1.0.0","generatorVersion":"0.3.0",
             "project":{"groupId":"dev.yuan.sample","artifactId":"asset-app",
             "packageName":"dev.yuan.sample.asset"},
             "entity":{"name":"Asset","table":"assets","apiPath":"/api/assets",
             "fields":[{"name":"sku","type":"string","required":true,"maxLength":64}],
             "permissions":{"read":"asset:read","write":"asset:write"}}}
            """;

    @Autowired MockMvc mvc;
    @Autowired PlatformStore platform;
    @Autowired ProjectDraftStore drafts;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private final UUID alpha = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();
    private final UUID alphaAdmin = UUID.randomUUID();
    private final UUID betaAdmin = UUID.randomUUID();
    private final UUID alphaReader = UUID.randomUUID();

    @BeforeAll
    void seedTenantsAndPermissions() {
        platform.insertTenant(alpha, "alpha", "Alpha tenant");
        platform.insertTenant(beta, "beta", "Beta tenant");
        platform.insertUser(alpha, alphaAdmin, "admin", "Alpha administrator", passwords.encode(ADMIN_PASSWORD));
        platform.insertUser(beta, betaAdmin, "admin", "Beta administrator", passwords.encode(ADMIN_PASSWORD));
        platform.insertUser(alpha, alphaReader, "reader", "Alpha reader", passwords.encode(READER_PASSWORD));
        UUID alphaAdminRole = UUID.randomUUID();
        UUID betaAdminRole = UUID.randomUUID();
        UUID readerRole = UUID.randomUUID();
        platform.insertRole(alpha, alphaAdminRole, "admin", "Admin");
        platform.insertRole(beta, betaAdminRole, "admin", "Admin");
        platform.insertRole(alpha, readerRole, "reader", "Reader");
        platform.assignRole(alpha, alphaAdmin, alphaAdminRole);
        platform.assignRole(beta, betaAdmin, betaAdminRole);
        platform.assignRole(alpha, alphaReader, readerRole);
        for (Permission permission : Permission.values()) {
            String code = permission.code().replace(':', '_');
            UUID alphaMenu = UUID.randomUUID();
            UUID betaMenu = UUID.randomUUID();
            platform.insertMenu(alpha, alphaMenu, code, code, permission.code(), "/studio/" + code);
            platform.insertMenu(beta, betaMenu, code, code, permission.code(), "/studio/" + code);
            platform.grantMenu(alpha, alphaAdminRole, alphaMenu);
            platform.grantMenu(beta, betaAdminRole, betaMenu);
            if (permission == Permission.PROJECT_DRAFT_READ) {
                platform.grantMenu(alpha, readerRole, alphaMenu);
            }
        }
    }

    @Test
    void migrationAndCreateReadUpdateArePersistentAndAudited() throws Exception {
        assertEquals(1L, count("SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '2' AND \"success\" = TRUE"));
        assertEquals(1L, count("SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '3' AND \"success\" = TRUE"));
        long before = count("SELECT COUNT(*) FROM project_drafts WHERE tenant_id = ?", alpha);
        UUID id = create("  Asset draft  ");
        mvc.perform(get("/api/v1/project-drafts/{id}", id).with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asset draft"))
                .andExpect(jsonPath("$.manifest.project.artifactId").value("asset-app"));
        String list = mvc.perform(get("/api/v1/project-drafts").with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(list.contains(id.toString()));
        assertEquals(before + 1, count("SELECT COUNT(*) FROM project_drafts WHERE tenant_id = ?", alpha));
        assertEquals(1L, count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND object_id = ? AND action = ?",
                alpha, id, "PROJECT_DRAFT_CREATED"));
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, true, "/api/v1/project-drafts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Renamed", MANIFEST, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.name").value("Renamed"));
        assertEquals(1L, count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND object_id = ? AND action = ?",
                alpha, id, "PROJECT_DRAFT_UPDATED"));
    }

    @Test
    void crossTenantIdsAreNotFoundAndCannotBeChanged() throws Exception {
        UUID id = create("Tenant boundary");
        mvc.perform(get("/api/v1/project-drafts/{id}", id).with(httpBasic("beta/admin", ADMIN_PASSWORD)))
                .andExpect(status().isNotFound());
        mvc.perform(csrfRequest("beta/admin", ADMIN_PASSWORD, true, "/api/v1/project-drafts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Intrusion", MANIFEST, 1)))
                .andExpect(status().isNotFound());
        String betaList = mvc.perform(get("/api/v1/project-drafts")
                        .with(httpBasic("beta/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(!betaList.contains(id.toString()));
        assertEquals(0L, count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND object_id = ?", beta, id));
    }

    @Test
    void roleAndCsrfBlockWritesButReaderMayRead() throws Exception {
        UUID id = create("Reader access");
        mvc.perform(get("/api/v1/project-drafts/{id}", id))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/project-drafts/{id}", id)
                        .with(httpBasic("alpha/reader", READER_PASSWORD)))
                .andExpect(status().isOk());
        mvc.perform(csrfRequest("alpha/reader", READER_PASSWORD, false, "/api/v1/project-drafts")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("Blocked", MANIFEST)))
                .andExpect(status().isForbidden());
        mvc.perform(csrfRequest("alpha/reader", READER_PASSWORD, true, "/api/v1/project-drafts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Blocked", MANIFEST, 1)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/project-drafts").with(httpBasic("alpha/admin", ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("No CSRF", MANIFEST)))
                .andExpect(status().isForbidden());
    }

    @Test
    void codePreviewRequiresWritePermissionAndReturnsUnavailableWithoutGenerator() throws Exception {
        String request = "{\"manifest\":" + MANIFEST + "}";
        mvc.perform(post("/api/v1/code-previews").with(httpBasic("alpha/admin", ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mvc.perform(csrfRequest("alpha/reader", READER_PASSWORD, false, "/api/v1/code-previews")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, false, "/api/v1/code-previews")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GENERATOR_UNAVAILABLE"));
    }

    @Test
    void workflowDraftValidationRequiresWritePermissionAndConfiguredConverter() throws Exception {
        String request = "{\"graph\":{\"schema_version\":\"0.1.0\"}}";
        mvc.perform(post("/api/v1/workflow-drafts/validate")
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mvc.perform(csrfRequest("alpha/reader", READER_PASSWORD, false,
                        "/api/v1/workflow-drafts/validate")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, false,
                        "/api/v1/workflow-drafts/validate")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STUDIO_GRAPH_UNAVAILABLE"));
    }

    @Test
    void codeExportRequiresWritePermissionAndConfiguredGenerator() throws Exception {
        String request = "{\"manifest\":" + MANIFEST + "}";
        long before = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND action = 'CODE_EXPORT'", alpha);
        mvc.perform(post("/api/v1/code-exports").with(httpBasic("alpha/admin", ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mvc.perform(csrfRequest("alpha/reader", READER_PASSWORD, false, "/api/v1/code-exports")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        assertEquals(before, count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND action = 'CODE_EXPORT'", alpha));
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, false, "/api/v1/code-exports")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GENERATOR_UNAVAILABLE"));
        assertEquals(before + 1, count("""
                SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND actor_user_id = ?
                AND action = 'CODE_EXPORT' AND object_type = 'SOURCE_EXPORT'
                AND outcome = 'FAILED' AND error_code = 'GENERATOR_UNAVAILABLE'
                AND LENGTH(manifest_sha256) = 64
                """, alpha, alphaAdmin));
        assertEquals(0L, count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND action = 'CODE_EXPORT'", beta));
    }

    @Test
    void invalidExportManifestIsRecordedAsRejectedWithoutManifestContent() throws Exception {
        String request = "{\"manifest\":[]}";
        long before = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND action = 'CODE_EXPORT' AND outcome = 'REJECTED'", alpha);
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, false, "/api/v1/code-exports")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MANIFEST"));
        assertEquals(before + 1, count("""
                SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND actor_user_id = ?
                AND action = 'CODE_EXPORT' AND outcome = 'REJECTED'
                AND error_code = 'INVALID_MANIFEST' AND LENGTH(manifest_sha256) = 64
                """, alpha, alphaAdmin));
        String alphaAudit = mvc.perform(get("/api/v1/audit")
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String betaAudit = mvc.perform(get("/api/v1/audit")
                        .with(httpBasic("beta/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(alphaAudit.contains("CODE_EXPORT"));
        assertTrue(alphaAudit.contains("REJECTED"));
        assertTrue(!alphaAudit.contains(ADMIN_PASSWORD));
        assertTrue(!alphaAudit.contains("\"manifest\""));
        assertTrue(!betaAudit.contains("CODE_EXPORT"));
    }

    @Test
    void invalidManifestNeverWritesDraftOrAudit() throws Exception {
        long before = count("SELECT COUNT(*) FROM project_drafts WHERE tenant_id = ?", alpha);
        long auditBefore = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ?", alpha);
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, false, "/api/v1/project-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Array", "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, false, "/api/v1/project-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Unknown schema", "{\"schemaVersion\":\"2.0.0\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, false, "/api/v1/project-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("No secrets", "{\"schemaVersion\":\"1.0.0\",\"apiKey\":\"synthetic-secret\"}")))
                .andExpect(status().isBadRequest());
        String oversizedManifest = "{\"schemaVersion\":\"1.0.0\",\"notes\":\"" + "中".repeat(22_000) + "\"}";
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, false, "/api/v1/project-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Too large", oversizedManifest)))
                .andExpect(status().isBadRequest());
        assertEquals(before, count("SELECT COUNT(*) FROM project_drafts WHERE tenant_id = ?", alpha));
        assertEquals(auditBefore, count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ?", alpha));
    }

    @Test
    void staleRevisionPreservesLatestDraftAndAddsNoAudit() throws Exception {
        UUID id = create("First version");
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, true, "/api/v1/project-drafts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Second version", MANIFEST, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.name").value("Second version"));
        long auditBefore = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND object_id = ?", alpha, id);
        mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, true, "/api/v1/project-drafts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Stale edit", MANIFEST, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(get("/api/v1/project-drafts/{id}", id).with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Second version"))
                .andExpect(jsonPath("$.revision").value(2));
        assertEquals(auditBefore, count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND object_id = ?", alpha, id));
    }

    @Test
    void databaseRejectsCrossTenantActorForDraft() {
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> drafts.insert(alpha, UUID.randomUUID(), "Bad actor", MANIFEST, betaAdmin));
    }

    @Test
    void cursorPagesExposeOlderDraftsAcrossTimestampTiesWithoutCrossingTenants() throws Exception {
        Instant newest = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant older = newest.minusSeconds(1);
        Set<UUID> seeded = new HashSet<>();
        for (int index = 0; index < 104; index++) {
            UUID id = UUID.randomUUID();
            seeded.add(id);
            drafts.insert(alpha, id, "Paged " + index, MANIFEST, alphaAdmin);
            jdbc.update("UPDATE project_drafts SET updated_at = ? WHERE tenant_id = ? AND id = ?",
                    Timestamp.from(index < 102 ? newest : older), alpha, id);
        }
        UUID betaId = UUID.randomUUID();
        drafts.insert(beta, betaId, "Other tenant", MANIFEST, betaAdmin);
        jdbc.update("UPDATE project_drafts SET updated_at = ? WHERE tenant_id = ? AND id = ?",
                Timestamp.from(newest.plusSeconds(1)), beta, betaId);

        JsonNode first = mapper.readTree(mvc.perform(get("/api/v1/project-drafts/page")
                        .param("limit", "100").with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(100))
                .andExpect(jsonPath("$.nextCursor.id").exists())
                .andReturn().getResponse().getContentAsString());
        String beforeTime = first.get("nextCursor").get("updatedAt").asText();
        String beforeId = first.get("nextCursor").get("id").asText();
        assertEquals(first.get("items").get(99).get("id").asText(), beforeId);
        assertEquals(first.get("items").get(99).get("updatedAt").asText(), beforeTime);
        JsonNode second = mapper.readTree(mvc.perform(get("/api/v1/project-drafts/page")
                        .param("limit", "100").param("beforeUpdatedAt", beforeTime)
                        .param("beforeId", beforeId).with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        List<UUID> first104 = new ArrayList<>();
        first.get("items").forEach(item -> first104.add(UUID.fromString(item.get("id").asText())));
        for (int index = 0; index < 4; index++) {
            first104.add(UUID.fromString(second.get("items").get(index).get("id").asText()));
        }
        assertEquals(104, new HashSet<>(first104).size());
        assertEquals(seeded, new HashSet<>(first104));
        assertEquals(beforeTime, second.get("items").get(0).get("updatedAt").asText());
        assertEquals(beforeTime, second.get("items").get(1).get("updatedAt").asText());
        assertTrue(!beforeTime.equals(second.get("items").get(2).get("updatedAt").asText()));
        assertTrue(!first.toString().contains(betaId.toString()));
        assertTrue(!second.toString().contains(betaId.toString()));

        mvc.perform(get("/api/v1/project-drafts/page")
                        .param("limit", "100").with(httpBasic("beta/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(betaId.toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(get("/api/v1/project-drafts/page")
                        .param("limit", "2").with(httpBasic("alpha/reader", READER_PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void cursorPageRejectsIncompleteAndOutOfRangeRequests() throws Exception {
        mvc.perform(get("/api/v1/project-drafts/page").param("limit", "0")
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/project-drafts/page").param("limit", "101")
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/project-drafts/page").param("beforeId", UUID.randomUUID().toString())
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/project-drafts/page").param("beforeUpdatedAt", "invalid")
                        .param("beforeId", UUID.randomUUID().toString())
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/project-drafts/page"))
                .andExpect(status().isUnauthorized());
    }

    private UUID create(String name) throws Exception {
        String body = mvc.perform(csrfRequest("alpha/admin", ADMIN_PASSWORD, false, "/api/v1/project-drafts")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(name, MANIFEST)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.manifest.schemaVersion").value("1.0.0"))
                .andExpect(jsonPath("$.revision").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(body);
        UUID id = UUID.fromString(node.get("id").asText());
        assertNotNull(node.get("createdAt"));
        return id;
    }

    private String createBody(String name, String manifest) {
        return "{\"name\":" + mapper.writeValueAsString(name) + ",\"manifest\":" + manifest + "}";
    }

    private String updateBody(String name, String manifest, long expectedRevision) {
        return "{\"name\":" + mapper.writeValueAsString(name) + ",\"manifest\":" + manifest
                + ",\"expectedRevision\":" + expectedRevision + "}";
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private MockHttpServletRequestBuilder csrfRequest(String login, String password, boolean update,
                                                      String path, Object... uriVariables) throws Exception {
        var tokenResult = mvc.perform(get("/api/v1/csrf").with(httpBasic(login, password)))
                .andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(tokenResult.getResponse().getContentAsString()).get("token").asText();
        Cookie cookie = tokenResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        MockHttpServletRequestBuilder request = update
                ? put(path, uriVariables) : post(path, uriVariables);
        return request.with(httpBasic(login, password)).cookie(cookie).header("X-XSRF-TOKEN", token);
    }
}
