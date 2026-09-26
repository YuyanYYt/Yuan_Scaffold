package dev.yuan.sample.asset.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapUser implements ApplicationRunner {
    private final AppUserRepository repository;
    private final PasswordEncoder encoder;
    @Value("${app.bootstrap.tenant:}") private String tenant;
    @Value("${app.bootstrap.username:}") private String username;
    @Value("${app.bootstrap.password:}") private String password;

    public BootstrapUser(AppUserRepository repository, PasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (tenant.isBlank() && username.isBlank() && password.isBlank()) return;
        if (!tenant.matches("[a-z][a-z0-9-]{1,31}") ||
                !username.matches("[a-z][a-z0-9._-]{1,31}") || password.length() < 12)
            throw new IllegalStateException("Set a valid bootstrap tenant, username and password (at least 12 characters)");
        String login = tenant + "/" + username;
        if (repository.count() > 0) {
            AppUser existing = repository.findByLogin(login)
                    .orElseThrow(() -> new IllegalStateException("Bootstrap identity differs from the initialized database"));
            if (!encoder.matches(password, existing.getPasswordHash()))
                throw new IllegalStateException("Bootstrap password differs from the initialized database");
            return;
        }
        repository.save(new AppUser(login, tenant, encoder.encode(password), "asset:read,asset:write"));
    }
}
