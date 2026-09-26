package dev.yuanscaffold.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthContext {
    private AuthContext() { }

    public static TenantPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof TenantPrincipal principal)) {
            throw new IllegalStateException("Authenticated tenant principal is required");
        }
        return principal;
    }
}
