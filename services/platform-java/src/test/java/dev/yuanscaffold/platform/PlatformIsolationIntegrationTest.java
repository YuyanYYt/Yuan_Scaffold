package dev.yuanscaffold.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.yuanscaffold.platform.security.Permission;
import dev.yuanscaffold.platform.store.PlatformStore;
import jakarta.servlet.http.Cookie;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:platform-isolation;DB_CLOSE_DELAY=-1",
        "platform.bootstrap.tenant-slug=",
        "platform.bootstrap.username=",
        "platform.bootstrap.password="
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformIsolationIntegrationTest {
    private static final String ADMIN_PASSWORD = "SyntheticAdminPassword123";
    private static final String READER_PASSWORD = "SyntheticReaderPassword123";

    @Autowired MockMvc mvc;
    @Autowired PlatformStore store;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private final UUID alpha = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();
    private final UUID alphaAdmin = UUID.randomUUID();
    private final UUID betaAdmin = UUID.randomUUID();
    private final UUID alphaReader = UUID.randomUUID();
    private final UUID alphaAdminRole = UUID.randomUUID();
    private final UUID betaAdminRole = UUID.randomUUID();
    private final UUID alphaReaderRole = UUID.randomUUID();
    private final UUID betaExtraRole = UUID.randomUUID();
    private final UUID betaExtraMenu = UUID.randomUUID();

    @BeforeAll
    void seedTenants() {
        store.insertTenant(alpha, "alpha", "Alpha tenant");
        store.insertTenant(beta, "beta", "Beta tenant");
        store.insertUser(alpha, alphaAdmin, "admin", "Alpha administrator", passwords.encode(ADMIN_PASSWORD));
        store.insertUser(beta, betaAdmin, "admin", "Beta administrator", passwords.encode(ADMIN_PASSWORD));
        store.insertUser(alpha, alphaReader, "reader", "Alpha reader", passwords.encode(READER_PASSWORD));
        store.insertRole(alpha, alphaAdminRole, "admin", "Administrator");
        store.insertRole(beta, betaAdminRole, "admin", "Administrator");
        store.insertRole(alpha, alphaReaderRole, "reader", "Reader");
        store.insertRole(beta, betaExtraRole, "extra", "Extra role");
        store.assignRole(alpha, alphaAdmin, alphaAdminRole);
        store.assignRole(beta, betaAdmin, betaAdminRole);
        store.assignRole(alpha, alphaReader, alphaReaderRole);
        for (Permission permission : Permission.values()) {
            UUID alphaMenu = UUID.randomUUID();
            UUID betaMenu = UUID.randomUUID();
            String code = permission.code().replace(':', '_');
            store.insertMenu(alpha, alphaMenu, code, code, permission.code(), "/system/" + code);
            store.insertMenu(beta, betaMenu, code, code, permission.code(), "/system/" + code);
            store.grantMenu(alpha, alphaAdminRole, alphaMenu);
            store.grantMenu(beta, betaAdminRole, betaMenu);
            if (permission == Permission.USER_READ) {
                store.grantMenu(alpha, alphaReaderRole, alphaMenu);
            }
        }
        store.insertMenu(beta, betaExtraMenu, "extra_menu", "Extra", Permission.USER_READ.code(), "/extra");
    }

    @Test
    void flywayMigratesSchemaBeforeApplicationStarts() {
        Integer applied = jdbc.queryForObject("SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = TRUE", Integer.class);
        assertNotNull(applied);
        assertTrue(applied >= 1);
    }

    @Test
    void principalTenantOverridesSpoofedHeaderAndOtherTenantIdsAreNotFound() throws Exception {
        mvc.perform(get("/api/v1/me").with(httpBasic("alpha/admin", ADMIN_PASSWORD))
                        .header("X-Tenant-ID", beta.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(alpha.toString()));

        mvc.perform(get("/api/v1/users/{id}", betaAdmin)
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/roles/{id}", betaExtraRole)
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/menus/{id}", betaExtraMenu)
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isNotFound());
        String users = mvc.perform(get("/api/v1/users")
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(users.contains(alphaAdmin.toString()));
        assertTrue(!users.contains(betaAdmin.toString()));
    }

    @Test
    void crossTenantRoleAndMenuGrantsDoNotWriteJoinRowsOrAudit() throws Exception {
        long before = count("SELECT COUNT(*) FROM user_roles WHERE tenant_id = ? AND role_id = ?", alpha, betaExtraRole);
        long auditBefore = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ?", alpha);
        mvc.perform(csrfPost("alpha/admin", ADMIN_PASSWORD,
                        "/api/v1/users/{userId}/roles/{roleId}", alphaReader, betaExtraRole))
                .andExpect(status().isNotFound());
        mvc.perform(csrfPost("alpha/admin", ADMIN_PASSWORD,
                        "/api/v1/users/{userId}/roles/{roleId}", betaAdmin, alphaAdminRole))
                .andExpect(status().isNotFound());
        assertEquals(before, count("SELECT COUNT(*) FROM user_roles WHERE tenant_id = ? AND role_id = ?", alpha, betaExtraRole));

        long roleMenuBefore = count("SELECT COUNT(*) FROM role_menus WHERE tenant_id = ? AND menu_id = ?", alpha, betaExtraMenu);
        mvc.perform(csrfPost("alpha/admin", ADMIN_PASSWORD,
                        "/api/v1/roles/{roleId}/menus/{menuId}", alphaAdminRole, betaExtraMenu))
                .andExpect(status().isNotFound());
        mvc.perform(csrfPost("alpha/admin", ADMIN_PASSWORD,
                        "/api/v1/roles/{roleId}/menus/{menuId}", betaExtraRole, betaExtraMenu))
                .andExpect(status().isNotFound());
        assertEquals(roleMenuBefore, count("SELECT COUNT(*) FROM role_menus WHERE tenant_id = ? AND menu_id = ?", alpha, betaExtraMenu));
        assertEquals(auditBefore, count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ?", alpha));
    }

    @Test
    void compositeForeignKeysRejectCrossTenantLinksEvenBelowServiceLayer() {
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> store.assignRole(alpha, alphaReader, betaExtraRole));
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> store.grantMenu(alpha, alphaAdminRole, betaExtraMenu));
    }

    @Test
    void permissionAndAuthenticationAreDistinctFromTenantMembership() throws Exception {
        mvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users").with(httpBasic("alpha/admin", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/users").with(httpBasic("alpha/reader", READER_PASSWORD)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/roles").with(httpBasic("alpha/reader", READER_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(csrfPost("alpha/reader", READER_PASSWORD, "/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"blocked\",\"displayName\":\"Blocked\",\"password\":\"SyntheticPassword123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void badUuidAndInvalidBodyUseStableErrorsWithoutWritingAudit() throws Exception {
        mvc.perform(get("/api/v1/users/not-a-uuid").with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        long auditBefore = count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ?", alpha);
        mvc.perform(csrfPost("alpha/admin", ADMIN_PASSWORD, "/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bad\",\"displayName\":\"Bad\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertEquals(auditBefore, count("SELECT COUNT(*) FROM audit_events WHERE tenant_id = ?", alpha));
    }

    @Test
    void unsafeMethodRequiresCsrfAndTokenExchangeAllowsMutation() throws Exception {
        String payload = "{\"username\":\"withcsrf\",\"displayName\":\"CSRF user\",\"password\":\"SyntheticPassword123\"}";
        mvc.perform(post("/api/v1/users").with(httpBasic("alpha/admin", ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());

        mvc.perform(csrfPost("alpha/admin", ADMIN_PASSWORD, "/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("withcsrf"));
        assertEquals(1, count("SELECT COUNT(*) FROM platform_users WHERE tenant_id = ? AND username = ?", alpha, "withcsrf"));
        assertEquals(0, count("SELECT COUNT(*) FROM platform_users WHERE tenant_id = ? AND username = ?", beta, "withcsrf"));
    }

    @Test
    void auditIsTenantScopedAndDoesNotContainPassword() throws Exception {
        mvc.perform(csrfPost("alpha/admin", ADMIN_PASSWORD, "/api/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"audit_probe\",\"name\":\"Audit probe\"}"))
                .andExpect(status().isCreated());
        String alphaAudit = mvc.perform(get("/api/v1/audit")
                        .with(httpBasic("alpha/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String betaAudit = mvc.perform(get("/api/v1/audit")
                        .with(httpBasic("beta/admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(alphaAudit.contains("ROLE_CREATED"));
        assertTrue(!betaAudit.contains("ROLE_CREATED"));
        assertTrue(!alphaAudit.contains(ADMIN_PASSWORD));
    }

    private long count(String sql, Object... params) {
        return jdbc.queryForObject(sql, Long.class, params);
    }

    private MockHttpServletRequestBuilder csrfPost(String login, String password,
                                                   String path, Object... uriVariables) throws Exception {
        var tokenResult = mvc.perform(get("/api/v1/csrf").with(httpBasic(login, password)))
                .andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(tokenResult.getResponse().getContentAsString()).get("token").asText();
        Cookie cookie = tokenResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        return post(path, uriVariables).with(httpBasic(login, password))
                .cookie(cookie).header("X-XSRF-TOKEN", token);
    }
}
