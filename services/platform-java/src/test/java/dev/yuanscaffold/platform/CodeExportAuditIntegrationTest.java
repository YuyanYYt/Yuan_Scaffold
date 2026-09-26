package dev.yuanscaffold.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.yuanscaffold.platform.generation.GeneratorPreviewService;
import dev.yuanscaffold.platform.security.Permission;
import dev.yuanscaffold.platform.store.PlatformStore;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:source-export-audit;DB_CLOSE_DELAY=-1",
        "platform.bootstrap.tenant-slug=",
        "platform.bootstrap.username=",
        "platform.bootstrap.password="
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeExportAuditIntegrationTest {
    private static final String PASSWORD = "SyntheticExportAuditPass123";
    private static final String MANIFEST = """
            {"schemaVersion":"1.0.0","project":{"artifactId":"audit-sample"}}
            """;

    @Autowired MockMvc mvc;
    @Autowired PlatformStore platform;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean GeneratorPreviewService preview;

    private final UUID alpha = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();
    private final UUID alphaAdmin = UUID.randomUUID();

    @BeforeAll
    void seed() {
        for (String slug : new String[] {"alpha", "beta"}) {
            UUID tenantId = slug.equals("alpha") ? alpha : beta;
            UUID userId = slug.equals("alpha") ? alphaAdmin : UUID.randomUUID();
            UUID roleId = UUID.randomUUID();
            platform.insertTenant(tenantId, slug, slug);
            platform.insertUser(tenantId, userId, "admin", "Admin", passwords.encode(PASSWORD));
            platform.insertRole(tenantId, roleId, "admin", "Admin");
            platform.assignRole(tenantId, userId, roleId);
            for (Permission permission : new Permission[] {Permission.PROJECT_DRAFT_WRITE, Permission.AUDIT_READ}) {
                UUID menuId = UUID.randomUUID();
                platform.insertMenu(tenantId, menuId, permission.code().replace(':', '_'),
                        permission.code(), permission.code(), "/" + permission.code());
                platform.grantMenu(tenantId, roleId, menuId);
            }
        }
    }

    @Test
    void successfulExportHasTenantScopedCorrelatableAuditWithoutManifestContents() throws Exception {
        when(preview.preview(any(JsonNode.class))).thenReturn(
                mapper.readTree("{\"previewSchemaVersion\":\"1.0.0\",\"files\":[],\"totalBytes\":0}"));
        JsonNode parsedManifest = mapper.readTree(MANIFEST);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((mapper.writeValueAsString(parsedManifest) + "\n").getBytes(StandardCharsets.UTF_8)));

        var csrf = mvc.perform(get("/api/v1/csrf").with(httpBasic("alpha/admin", PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse();
        String token = mapper.readTree(csrf.getContentAsString()).get("token").asText();
        Cookie cookie = csrf.getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        var response = mvc.perform(post("/api/v1/code-exports")
                        .with(httpBasic("alpha/admin", PASSWORD)).cookie(cookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manifest\":" + MANIFEST + "}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("DRAFT_UNVERIFIED", response.getHeader("X-Yuan-Source-Status"));
        assertEquals("application/zip", response.getContentType());
        assertTrue(response.getContentAsByteArray().length > 0);
        try (var archive = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            ZipEntry manifestEntry = archive.getNextEntry();
            assertNotNull(manifestEntry);
            assertEquals("yuan-manifest.json", manifestEntry.getName());
            assertEquals(expectedHash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(archive.readAllBytes())));
        }
        UUID exportId = UUID.fromString(response.getHeader("X-Yuan-Export-Audit-Id"));
        Map<String, Object> event = jdbc.queryForMap("""
                SELECT tenant_id, actor_user_id, manifest_sha256, outcome, error_code
                FROM audit_events WHERE object_id = ? AND action = 'CODE_EXPORT'
                """, exportId);
        assertEquals(alpha, event.get("tenant_id"));
        assertEquals(alphaAdmin, event.get("actor_user_id"));
        assertEquals(expectedHash, event.get("manifest_sha256"));
        assertEquals("SUCCESS", event.get("outcome"));
        assertNull(event.get("error_code"));

        String alphaAudit = mvc.perform(get("/api/v1/audit").with(httpBasic("alpha/admin", PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String betaAudit = mvc.perform(get("/api/v1/audit").with(httpBasic("beta/admin", PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(alphaAudit.contains(exportId.toString()));
        assertTrue(alphaAudit.contains(expectedHash));
        assertFalse(alphaAudit.contains("audit-sample"));
        assertFalse(alphaAudit.contains(PASSWORD));
        assertFalse(betaAudit.contains(exportId.toString()));
    }
}
