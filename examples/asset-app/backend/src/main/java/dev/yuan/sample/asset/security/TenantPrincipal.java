package dev.yuan.sample.asset.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

public final class TenantPrincipal implements UserDetails {
    private final String login;
    private final String tenantSlug;
    private final String passwordHash;
    private final Collection<? extends GrantedAuthority> authorities;

    public TenantPrincipal(AppUser user) {
        this.login = user.getLogin();
        this.tenantSlug = user.getTenantSlug();
        this.passwordHash = user.getPasswordHash();
        this.authorities = AuthorityUtils.commaSeparatedStringToAuthorityList(user.getPermissions());
    }
    public String tenantSlug() { return tenantSlug; }
    @Override public String getUsername() { return login; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
