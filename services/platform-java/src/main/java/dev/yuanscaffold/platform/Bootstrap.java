package dev.yuanscaffold.platform;

import dev.yuanscaffold.platform.security.Permission;
import dev.yuanscaffold.platform.store.PlatformStore;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class Bootstrap {
    @Bean
    ApplicationRunner initialTenant(
            PlatformStore store,
            PasswordEncoder passwords,
            TransactionTemplate transactions,
            @Value("${platform.bootstrap.tenant-slug:}") String tenantSlug,
            @Value("${platform.bootstrap.tenant-name:}") String tenantName,
            @Value("${platform.bootstrap.username:}") String username,
            @Value("${platform.bootstrap.password:}") String password
    ) {
        return args -> {
            if (tenantSlug.isBlank() && username.isBlank() && password.isBlank()) {
                return;
            }
            if (!tenantSlug.matches("[a-z][a-z0-9-]{2,62}")
                    || !username.matches("[a-z][a-z0-9_.-]{2,62}")
                    || password.length() < 12) {
                throw new IllegalStateException("Bootstrap requires valid tenant slug, username and a 12+ character password");
            }
            transactions.executeWithoutResult(status -> {
                if (store.findTenantId(tenantSlug).isPresent()) {
                    return; // Restart does not reassign or rotate administrator credentials.
                }
                if (store.tenantCount() != 0) {
                    throw new IllegalStateException("Bootstrap only provisions an empty database");
                }
                UUID tenantId = UUID.randomUUID();
                UUID adminId = UUID.randomUUID();
                UUID roleId = UUID.randomUUID();
                store.insertTenant(tenantId, tenantSlug, tenantName.isBlank() ? tenantSlug : tenantName);
                store.insertUser(tenantId, adminId, username, "Initial administrator", passwords.encode(password));
                store.insertRole(tenantId, roleId, "platform_admin", "Platform administrator");
                store.assignRole(tenantId, adminId, roleId);
                for (Permission permission : Permission.values()) {
                    UUID menuId = UUID.randomUUID();
                    String code = permission.code().replace(':', '_');
                    store.insertMenu(tenantId, menuId, code, permission.code(),
                            permission.code(), "/system/" + code);
                    store.grantMenu(tenantId, roleId, menuId);
                }
                store.insertAudit(tenantId, adminId, "TENANT_BOOTSTRAPPED", "TENANT", tenantId);
            });
        };
    }
}
