package dev.yuanscaffold.platform.security;

import dev.yuanscaffold.platform.store.PlatformStore;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class TenantUserDetailsService implements UserDetailsService {
    private final PlatformStore store;

    public TenantUserDetailsService(PlatformStore store) {
        this.store = store;
    }

    @Override
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        int separator = login.indexOf('/');
        if (separator <= 0 || separator == login.length() - 1 || login.indexOf('/', separator + 1) >= 0) {
            throw new UsernameNotFoundException("Invalid login");
        }
        String slug = login.substring(0, separator);
        String username = login.substring(separator + 1);
        return store.findPrincipal(slug, username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown or disabled account"));
    }
}
