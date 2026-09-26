package dev.yuanscaffold.platform.store;

import dev.yuanscaffold.platform.security.TenantPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlatformStore {
    private final JdbcTemplate jdbc;

    public PlatformStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantPrincipal> findPrincipal(String tenantSlug, String username) {
        String sql = """
                SELECT t.id AS tenant_id, t.slug, u.id AS user_id, u.username, u.password_hash, u.enabled
                FROM tenants t JOIN platform_users u ON u.tenant_id = t.id
                WHERE t.slug = ? AND u.username = ? AND t.enabled = TRUE AND u.enabled = TRUE
                """;
        try {
            return Optional.of(jdbc.queryForObject(sql, (rs, row) -> {
                UUID tenantId = id(rs, "tenant_id");
                UUID userId = id(rs, "user_id");
                List<String> permissions = jdbc.queryForList("""
                        SELECT DISTINCT m.permission_code FROM menus m
                        JOIN role_menus rm ON rm.tenant_id = m.tenant_id AND rm.menu_id = m.id
                        JOIN user_roles ur ON ur.tenant_id = rm.tenant_id AND ur.role_id = rm.role_id
                        WHERE ur.tenant_id = ? AND ur.user_id = ?
                        """, String.class, tenantId, userId);
                return new TenantPrincipal(tenantId, userId, rs.getString("slug"),
                        rs.getString("username"), rs.getString("password_hash"),
                        rs.getBoolean("enabled"), List.copyOf(permissions));
            }, tenantSlug, username));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public long tenantCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tenants", Long.class);
    }

    public Optional<UUID> findTenantId(String slug) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id FROM tenants WHERE slug = ?", UUID.class, slug));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public void insertTenant(UUID id, String slug, String displayName) {
        jdbc.update("INSERT INTO tenants (id, slug, display_name) VALUES (?, ?, ?)", id, slug, displayName);
    }

    public void insertUser(UUID tenantId, UUID id, String username, String displayName, String passwordHash) {
        jdbc.update("""
                INSERT INTO platform_users (id, tenant_id, username, display_name, password_hash)
                VALUES (?, ?, ?, ?, ?)
                """, id, tenantId, username, displayName, passwordHash);
    }

    public Optional<UserView> findUser(UUID tenantId, UUID userId) {
        try {
            return Optional.of(jdbc.queryForObject("""
                    SELECT id, username, display_name, enabled, created_at FROM platform_users
                    WHERE tenant_id = ? AND id = ?
                    """, PlatformStore::user, tenantId, userId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<UserView> listUsers(UUID tenantId) {
        return jdbc.query("""
                SELECT id, username, display_name, enabled, created_at FROM platform_users
                WHERE tenant_id = ? ORDER BY username LIMIT 100
                """, PlatformStore::user, tenantId);
    }

    public void insertRole(UUID tenantId, UUID roleId, String code, String name) {
        jdbc.update("INSERT INTO roles (id, tenant_id, code, name) VALUES (?, ?, ?, ?)",
                roleId, tenantId, code, name);
    }

    public Optional<RoleView> findRole(UUID tenantId, UUID roleId) {
        try {
            return Optional.of(jdbc.queryForObject("""
                    SELECT id, code, name, created_at FROM roles WHERE tenant_id = ? AND id = ?
                    """, PlatformStore::role, tenantId, roleId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<RoleView> listRoles(UUID tenantId) {
        return jdbc.query("""
                SELECT id, code, name, created_at FROM roles
                WHERE tenant_id = ? ORDER BY code LIMIT 100
                """, PlatformStore::role, tenantId);
    }

    public void insertMenu(UUID tenantId, UUID menuId, String code, String title,
                           String permissionCode, String path) {
        jdbc.update("""
                INSERT INTO menus (id, tenant_id, code, title, permission_code, path)
                VALUES (?, ?, ?, ?, ?, ?)
                """, menuId, tenantId, code, title, permissionCode, path);
    }

    public Optional<MenuView> findMenu(UUID tenantId, UUID menuId) {
        try {
            return Optional.of(jdbc.queryForObject("""
                    SELECT id, code, title, permission_code, path, created_at
                    FROM menus WHERE tenant_id = ? AND id = ?
                    """, PlatformStore::menu, tenantId, menuId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<MenuView> listMenus(UUID tenantId) {
        return jdbc.query("""
                SELECT id, code, title, permission_code, path, created_at
                FROM menus WHERE tenant_id = ? ORDER BY code LIMIT 100
                """, PlatformStore::menu, tenantId);
    }

    public void assignRole(UUID tenantId, UUID userId, UUID roleId) {
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_roles WHERE tenant_id = ? AND user_id = ? AND role_id = ?
                """, Long.class, tenantId, userId, roleId) == 0) {
            jdbc.update("INSERT INTO user_roles (tenant_id, user_id, role_id) VALUES (?, ?, ?)",
                    tenantId, userId, roleId);
        }
    }

    public void grantMenu(UUID tenantId, UUID roleId, UUID menuId) {
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM role_menus WHERE tenant_id = ? AND role_id = ? AND menu_id = ?
                """, Long.class, tenantId, roleId, menuId) == 0) {
            jdbc.update("INSERT INTO role_menus (tenant_id, role_id, menu_id) VALUES (?, ?, ?)",
                    tenantId, roleId, menuId);
        }
    }

    public void insertAudit(UUID tenantId, UUID actorUserId, String action,
                            String objectType, UUID objectId) {
        jdbc.update("""
                INSERT INTO audit_events (id, tenant_id, actor_user_id, action, object_type, object_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, actorUserId, action, objectType, objectId);
    }

    public void insertSourceExportAudit(UUID tenantId, UUID actorUserId, UUID exportId,
                                        String manifestSha256, String outcome, String errorCode) {
        jdbc.update("""
                INSERT INTO audit_events (id, tenant_id, actor_user_id, action, object_type, object_id,
                                          manifest_sha256, outcome, error_code)
                VALUES (?, ?, ?, 'CODE_EXPORT', 'SOURCE_EXPORT', ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, actorUserId, exportId,
                manifestSha256, outcome, errorCode);
    }

    public List<AuditView> listAudit(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT id, actor_user_id, action, object_type, object_id,
                       manifest_sha256, outcome, error_code, created_at
                FROM audit_events WHERE tenant_id = ? ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new AuditView(id(rs, "id"), id(rs, "actor_user_id"),
                rs.getString("action"), rs.getString("object_type"), id(rs, "object_id"),
                rs.getString("manifest_sha256"), rs.getString("outcome"), rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant()), tenantId, limit);
    }

    private static UUID id(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static UserView user(ResultSet rs, int row) throws SQLException {
        return new UserView(id(rs, "id"), rs.getString("username"), rs.getString("display_name"),
                rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant());
    }

    private static RoleView role(ResultSet rs, int row) throws SQLException {
        return new RoleView(id(rs, "id"), rs.getString("code"), rs.getString("name"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static MenuView menu(ResultSet rs, int row) throws SQLException {
        return new MenuView(id(rs, "id"), rs.getString("code"), rs.getString("title"),
                rs.getString("permission_code"), rs.getString("path"),
                rs.getTimestamp("created_at").toInstant());
    }

    public record UserView(UUID id, String username, String displayName, boolean enabled, Instant createdAt) { }
    public record RoleView(UUID id, String code, String name, Instant createdAt) { }
    public record MenuView(UUID id, String code, String title, String permissionCode,
                           String path, Instant createdAt) { }
    public record AuditView(UUID id, UUID actorUserId, String action, String objectType,
                            UUID objectId, String manifestSha256, String outcome,
                            String errorCode, Instant createdAt) { }
}
