package dev.yuan.sample.asset.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class AppUser {
    @Id @Column(length = 36, nullable = false)
    private String id;
    @Column(nullable = false, unique = true, length = 80)
    private String login;
    @Column(name = "tenant_slug", nullable = false, length = 64)
    private String tenantSlug;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false, length = 256)
    private String permissions;

    protected AppUser() { }
    public AppUser(String login, String tenantSlug, String passwordHash, String permissions) {
        this.id = UUID.randomUUID().toString();
        this.login = login;
        this.tenantSlug = tenantSlug;
        this.passwordHash = passwordHash;
        this.permissions = permissions;
    }
    public String getLogin() { return login; }
    public String getTenantSlug() { return tenantSlug; }
    public String getPasswordHash() { return passwordHash; }
    public String getPermissions() { return permissions; }
}
