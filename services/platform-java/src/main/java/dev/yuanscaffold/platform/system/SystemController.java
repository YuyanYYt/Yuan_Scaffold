package dev.yuanscaffold.platform.system;

import dev.yuanscaffold.platform.security.AuthContext;
import dev.yuanscaffold.platform.store.PlatformStore.AuditView;
import dev.yuanscaffold.platform.store.PlatformStore.MenuView;
import dev.yuanscaffold.platform.store.PlatformStore.RoleView;
import dev.yuanscaffold.platform.store.PlatformStore.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1")
public class SystemController {
    private final SystemService service;

    public SystemController(SystemService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public Me me() {
        var principal = AuthContext.current();
        return new Me(principal.tenantId(), principal.userId(), principal.tenantSlug(),
                principal.localUsername(), principal.permissions());
    }

    @GetMapping("/csrf")
    public Csrf csrf(@RequestAttribute("_csrf") CsrfToken token) {
        return new Csrf(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('system:user:read')")
    public List<UserView> users() {
        return service.users();
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAuthority('system:user:read')")
    public UserView user(@PathVariable UUID id) {
        return service.user(id);
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('system:user:write')")
    public ResponseEntity<UserView> createUser(@Valid @RequestBody CreateUser request) {
        UserView created = service.createUser(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @PostMapping("/users/{userId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('system:role:write')")
    public ResponseEntity<Void> assignRole(@PathVariable UUID userId, @PathVariable UUID roleId) {
        service.assignRole(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('system:role:read')")
    public List<RoleView> roles() {
        return service.roles();
    }

    @GetMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('system:role:read')")
    public RoleView role(@PathVariable UUID id) {
        return service.role(id);
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('system:role:write')")
    public ResponseEntity<RoleView> createRole(@Valid @RequestBody CreateRole request) {
        RoleView created = service.createRole(request);
        return ResponseEntity.created(URI.create("/api/v1/roles/" + created.id())).body(created);
    }

    @PostMapping("/roles/{roleId}/menus/{menuId}")
    @PreAuthorize("hasAuthority('system:role:write')")
    public ResponseEntity<Void> grantMenu(@PathVariable UUID roleId, @PathVariable UUID menuId) {
        service.grantMenu(roleId, menuId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/menus")
    @PreAuthorize("hasAuthority('system:menu:read')")
    public List<MenuView> menus() {
        return service.menus();
    }

    @GetMapping("/menus/{id}")
    @PreAuthorize("hasAuthority('system:menu:read')")
    public MenuView menu(@PathVariable UUID id) {
        return service.menu(id);
    }

    @PostMapping("/menus")
    @PreAuthorize("hasAuthority('system:menu:write')")
    public ResponseEntity<MenuView> createMenu(@Valid @RequestBody CreateMenu request) {
        MenuView created = service.createMenu(request);
        return ResponseEntity.created(URI.create("/api/v1/menus/" + created.id())).body(created);
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('system:audit:read')")
    public List<AuditView> audit(@RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) {
            throw dev.yuanscaffold.platform.web.ApiException.invalid("limit must be between 1 and 100");
        }
        return service.audit(limit);
    }

    public record Me(UUID tenantId, UUID userId, String tenantSlug, String username,
                     List<String> permissions) { }

    public record Csrf(String headerName, String token) { }

    public record CreateUser(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.-]{2,62}") String username,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(min = 12, max = 256) String password
    ) { }

    public record CreateRole(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.-]{2,62}") String code,
            @NotBlank @Size(max = 120) String name
    ) { }

    public record CreateMenu(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.-]{2,62}") String code,
            @NotBlank @Size(max = 120) String title,
            @NotBlank String permissionCode,
            @NotBlank @Pattern(regexp = "/[a-z0-9/_-]{0,199}") String path
    ) { }
}
