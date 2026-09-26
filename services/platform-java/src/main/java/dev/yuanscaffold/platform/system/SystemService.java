package dev.yuanscaffold.platform.system;

import dev.yuanscaffold.platform.security.AuthContext;
import dev.yuanscaffold.platform.security.Permission;
import dev.yuanscaffold.platform.security.TenantPrincipal;
import dev.yuanscaffold.platform.store.PlatformStore;
import dev.yuanscaffold.platform.store.PlatformStore.AuditView;
import dev.yuanscaffold.platform.store.PlatformStore.MenuView;
import dev.yuanscaffold.platform.store.PlatformStore.RoleView;
import dev.yuanscaffold.platform.store.PlatformStore.UserView;
import dev.yuanscaffold.platform.web.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemService {
    private final PlatformStore store;
    private final PasswordEncoder passwords;

    public SystemService(PlatformStore store, PasswordEncoder passwords) {
        this.store = store;
        this.passwords = passwords;
    }

    @Transactional(readOnly = true)
    public List<UserView> users() {
        return store.listUsers(AuthContext.current().tenantId());
    }

    @Transactional(readOnly = true)
    public UserView user(UUID id) {
        return store.findUser(AuthContext.current().tenantId(), id)
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional
    public UserView createUser(SystemController.CreateUser request) {
        TenantPrincipal principal = AuthContext.current();
        UUID id = UUID.randomUUID();
        store.insertUser(principal.tenantId(), id, request.username(), request.displayName(),
                passwords.encode(request.password()));
        store.insertAudit(principal.tenantId(), principal.userId(), "USER_CREATED", "USER", id);
        return store.findUser(principal.tenantId(), id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<RoleView> roles() {
        return store.listRoles(AuthContext.current().tenantId());
    }

    @Transactional(readOnly = true)
    public RoleView role(UUID id) {
        return store.findRole(AuthContext.current().tenantId(), id)
                .orElseThrow(() -> ApiException.notFound("Role"));
    }

    @Transactional
    public RoleView createRole(SystemController.CreateRole request) {
        TenantPrincipal principal = AuthContext.current();
        UUID id = UUID.randomUUID();
        store.insertRole(principal.tenantId(), id, request.code(), request.name());
        store.insertAudit(principal.tenantId(), principal.userId(), "ROLE_CREATED", "ROLE", id);
        return store.findRole(principal.tenantId(), id).orElseThrow();
    }

    @Transactional
    public void assignRole(UUID userId, UUID roleId) {
        TenantPrincipal principal = AuthContext.current();
        UUID tenantId = principal.tenantId();
        store.findUser(tenantId, userId).orElseThrow(() -> ApiException.notFound("User"));
        store.findRole(tenantId, roleId).orElseThrow(() -> ApiException.notFound("Role"));
        store.assignRole(tenantId, userId, roleId);
        store.insertAudit(tenantId, principal.userId(), "ROLE_ASSIGNED", "USER", userId);
    }

    @Transactional(readOnly = true)
    public List<MenuView> menus() {
        return store.listMenus(AuthContext.current().tenantId());
    }

    @Transactional(readOnly = true)
    public MenuView menu(UUID id) {
        return store.findMenu(AuthContext.current().tenantId(), id)
                .orElseThrow(() -> ApiException.notFound("Menu"));
    }

    @Transactional
    public MenuView createMenu(SystemController.CreateMenu request) {
        if (!Permission.contains(request.permissionCode())) {
            throw ApiException.invalid("Unknown permission code");
        }
        TenantPrincipal principal = AuthContext.current();
        UUID id = UUID.randomUUID();
        store.insertMenu(principal.tenantId(), id, request.code(), request.title(),
                request.permissionCode(), request.path());
        store.insertAudit(principal.tenantId(), principal.userId(), "MENU_CREATED", "MENU", id);
        return store.findMenu(principal.tenantId(), id).orElseThrow();
    }

    @Transactional
    public void grantMenu(UUID roleId, UUID menuId) {
        TenantPrincipal principal = AuthContext.current();
        UUID tenantId = principal.tenantId();
        store.findRole(tenantId, roleId).orElseThrow(() -> ApiException.notFound("Role"));
        store.findMenu(tenantId, menuId).orElseThrow(() -> ApiException.notFound("Menu"));
        store.grantMenu(tenantId, roleId, menuId);
        store.insertAudit(tenantId, principal.userId(), "MENU_GRANTED", "ROLE", roleId);
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit(int limit) {
        return store.listAudit(AuthContext.current().tenantId(), limit);
    }
}
