import { createHash } from 'node:crypto';

const javaType = { string: 'String', integer: 'Integer', boolean: 'Boolean' };
const sqlType = field => ({ string: `VARCHAR(${field.maxLength})`, integer: 'INTEGER', boolean: 'BOOLEAN' })[field.type];
const column = name => name.replace(/[A-Z]/g, match => `_${match.toLowerCase()}`);
const cap = name => name[0].toUpperCase() + name.slice(1);
const uniqueIndexName = (table, fieldColumn) => `uk_${table.slice(0, 20)}_${fieldColumn.slice(0, 20)}_${createHash('sha256').update(`${table}:${fieldColumn}`).digest('hex').slice(0, 8)}`;

function pom(manifest) {
  const { groupId, artifactId } = manifest.project;
  return `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>
  <groupId>${groupId}</groupId>
  <artifactId>${artifactId}</artifactId>
  <version>0.3.0-SNAPSHOT</version>
  <properties><java.version>21</java.version></properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
`;
}

function entityClass(m) {
  const p = m.project.packageName;
  const e = m.entity;
  const declarations = e.fields.map(f => `    @Column(name = "${column(f.name)}", nullable = ${!f.required}${f.type === 'string' ? `, length = ${f.maxLength}` : ''})
    private ${javaType[f.type]} ${f.name};`).join('\n\n');
  const assignments = e.fields.map(f => `        this.${f.name} = request.${f.name}();`).join('\n');
  const getters = e.fields.map(f => `    public ${javaType[f.type]} get${cap(f.name)}() { return ${f.name}; }`).join('\n');
  return `package ${p}.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ${p}.api.${e.name}Request;

@Entity
@Table(name = "${e.table}")
public class ${e.name} {
    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "tenant_slug", nullable = false, length = 64)
    private String tenantSlug;

${declarations}

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ${e.name}() { }

    public ${e.name}(String tenantSlug, ${e.name}Request request) {
        this.id = UUID.randomUUID().toString();
        this.tenantSlug = tenantSlug;
        this.createdAt = Instant.now();
        apply(request);
    }

    public void apply(${e.name}Request request) {
${assignments}
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantSlug() { return tenantSlug; }
${getters}
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
`;
}

function requestClass(m) {
  const e = m.entity;
  const fields = e.fields.map(f => {
    const annotations = [];
    if (f.type === 'string') {
      if (f.required) annotations.push('@NotBlank');
      annotations.push(`@Size(max = ${f.maxLength})`);
    } else if (f.required) annotations.push('@NotNull');
    if (f.minimum !== undefined) annotations.push(`@Min(${f.minimum})`);
    return `        ${annotations.join(' ')} ${javaType[f.type]} ${f.name}`;
  }).join(',\n');
  return `package ${m.project.packageName}.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ${e.name}Request(
${fields}
) { }
`;
}

function responseClass(m) {
  const e = m.entity;
  const fields = e.fields.map(f => `        ${javaType[f.type]} ${f.name}`).join(',\n');
  const values = e.fields.map(f => `entity.get${cap(f.name)}()`).join(', ');
  return `package ${m.project.packageName}.api;

import java.time.Instant;
import ${m.project.packageName}.domain.${e.name};

public record ${e.name}Response(
        String id,
${fields},
        Instant createdAt,
        Instant updatedAt
) {
    public static ${e.name}Response from(${e.name} entity) {
        return new ${e.name}Response(entity.getId(), ${values}, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
`;
}

function repositoryClass(m) {
  const e = m.entity;
  return `package ${m.project.packageName}.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ${e.name}Repository extends JpaRepository<${e.name}, String> {
    Page<${e.name}> findAllByTenantSlug(String tenantSlug, Pageable pageable);
    Optional<${e.name}> findByIdAndTenantSlug(String id, String tenantSlug);
}
`;
}

function serviceClass(m) {
  const p = m.project.packageName;
  const e = m.entity;
  return `package ${p}.domain;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ${p}.api.${e.name}Request;
import ${p}.api.${e.name}Response;

@Service
public class ${e.name}Service {
    private final ${e.name}Repository repository;

    public ${e.name}Service(${e.name}Repository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public List<${e.name}Response> list(String tenantSlug, int page, int size) {
        return repository.findAllByTenantSlug(tenantSlug, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(${e.name}Response::from).getContent();
    }

    @Transactional(readOnly = true)
    public ${e.name}Response get(String tenantSlug, String id) {
        return ${e.name}Response.from(find(tenantSlug, id));
    }

    @Transactional
    public ${e.name}Response create(String tenantSlug, ${e.name}Request request) {
        return ${e.name}Response.from(repository.save(new ${e.name}(tenantSlug, request)));
    }

    @Transactional
    public ${e.name}Response update(String tenantSlug, String id, ${e.name}Request request) {
        ${e.name} entity = find(tenantSlug, id);
        entity.apply(request);
        return ${e.name}Response.from(entity);
    }

    @Transactional
    public void delete(String tenantSlug, String id) { repository.delete(find(tenantSlug, id)); }

    private ${e.name} find(String tenantSlug, String id) {
        return repository.findByIdAndTenantSlug(id, tenantSlug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
`;
}

function controllerClass(m) {
  const p = m.project.packageName;
  const e = m.entity;
  return `package ${p}.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ${p}.domain.${e.name}Service;
import ${p}.security.TenantPrincipal;

@RestController
@Validated
@RequestMapping("${e.apiPath}")
public class ${e.name}Controller {
    private final ${e.name}Service service;

    public ${e.name}Controller(${e.name}Service service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('${e.permissions.read}')")
    public List<${e.name}Response> list(@AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(principal.tenantSlug(), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('${e.permissions.read}')")
    public ${e.name}Response get(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable String id) {
        return service.get(principal.tenantSlug(), id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('${e.permissions.write}')")
    public ResponseEntity<${e.name}Response> create(@AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody ${e.name}Request request) {
        ${e.name}Response created = service.create(principal.tenantSlug(), request);
        return ResponseEntity.created(URI.create("${e.apiPath}/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('${e.permissions.write}')")
    public ${e.name}Response update(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable String id,
            @Valid @RequestBody ${e.name}Request request) {
        return service.update(principal.tenantSlug(), id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('${e.permissions.write}')")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable String id) {
        service.delete(principal.tenantSlug(), id);
        return ResponseEntity.noContent().build();
    }
}
`;
}

function securityClasses(m) {
  const p = m.project.packageName;
  const permissions = `${m.entity.permissions.read},${m.entity.permissions.write}`;
  return new Map([
    ['security/AppUser.java', `package ${p}.security;

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
`],
    ['security/AppUserRepository.java', `package ${p}.security;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, String> {
    Optional<AppUser> findByLogin(String login);
}
`],
    ['security/TenantPrincipal.java', `package ${p}.security;

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
`],
    ['security/SecurityConfig.java', `package ${p}.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/api/csrf").permitAll().anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(AppUserRepository repository) {
        return login -> repository.findByLogin(login).map(TenantPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }
}
`],
    ['security/CsrfController.java', `package ${p}.security;

import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    @GetMapping("/api/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) { return Map.of("token", csrfToken.getToken()); }
}
`],
    ['security/BootstrapUser.java', `package ${p}.security;

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
    @Value("\${app.bootstrap.tenant:}") private String tenant;
    @Value("\${app.bootstrap.username:}") private String username;
    @Value("\${app.bootstrap.password:}") private String password;

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
        repository.save(new AppUser(login, tenant, encoder.encode(password), "${permissions}"));
    }
}
`]
  ]);
}

function migration(m) {
  const e = m.entity;
  const columns = e.fields.map(f => `    ${column(f.name)} ${sqlType(f)} ${f.required ? 'NOT NULL' : 'NULL'}`).join(',\n');
  const uniqueIndexes = e.fields.filter(f => f.unique).map(f => `CREATE UNIQUE INDEX ${uniqueIndexName(e.table, column(f.name))} ON ${e.table}(tenant_slug, ${column(f.name)});`).join('\n');
  return `CREATE TABLE app_users (
    id VARCHAR(36) PRIMARY KEY,
    login VARCHAR(80) NOT NULL UNIQUE,
    tenant_slug VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    permissions VARCHAR(256) NOT NULL
);

CREATE TABLE ${e.table} (
    id VARCHAR(36) PRIMARY KEY,
    tenant_slug VARCHAR(64) NOT NULL,
${columns},
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_${e.table}_tenant ON ${e.table}(tenant_slug);
${uniqueIndexes ? `${uniqueIndexes}\n` : ''}`;
}

function integrationTest(m) {
  const p = m.project.packageName;
  const e = m.entity;
  const values = Object.fromEntries(e.fields.map(f => [f.name, f.type === 'string' ? `sample-${f.name}` : f.type === 'integer' ? Math.max(f.minimum ?? 0, 5) : true]));
  const updated = { ...values };
  const first = e.fields[0];
  updated[first.name] = first.type === 'string' ? `updated-${first.name}` : first.type === 'integer' ? values[first.name] + 1 : false;
  const body = JSON.stringify(JSON.stringify(values));
  const updatedBody = JSON.stringify(JSON.stringify(updated));
  return `package ${p};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import ${p}.domain.${e.name}Repository;
import ${p}.security.AppUser;
import ${p}.security.AppUserRepository;
import ${p}.security.BootstrapUser;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:generatedtest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GeneratedApplicationTest {
    private static final String PATH = "${e.apiPath}";
    private static final String BODY = ${body};
    private static final String UPDATED_BODY = ${updatedBody};

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired ${e.name}Repository records;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        records.deleteAll();
        users.deleteAll();
        users.save(new AppUser("alpha/editor", "alpha", encoder.encode("test-secret-123"), "${e.permissions.read},${e.permissions.write}"));
        users.save(new AppUser("beta/editor", "beta", encoder.encode("test-secret-123"), "${e.permissions.read},${e.permissions.write}"));
        users.save(new AppUser("alpha/reader", "alpha", encoder.encode("test-secret-123"), "${e.permissions.read}"));
    }

    @Test
    void authenticatesAndScopesCrudToPrincipalTenant() throws Exception {
        var created = mvc.perform(post(PATH).with(httpBasic("alpha/editor", "test-secret-123"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andReturn();
        String location = created.getResponse().getHeader("Location");
        assertThat(location).startsWith(PATH + "/");
        String id = location.substring((PATH + "/").length());

        mvc.perform(get(PATH + "/" + id).with(httpBasic("alpha/editor", "test-secret-123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenantSlug").doesNotExist());
        mvc.perform(get(PATH).header("X-Tenant", "beta").with(httpBasic("alpha/editor", "test-secret-123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get(PATH).with(httpBasic("beta/editor", "test-secret-123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get(PATH + "/" + id).with(httpBasic("beta/editor", "test-secret-123")))
                .andExpect(status().isNotFound());
        mvc.perform(put(PATH + "/" + id).with(httpBasic("beta/editor", "test-secret-123"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(UPDATED_BODY))
                .andExpect(status().isNotFound());
        mvc.perform(delete(PATH + "/" + id).with(httpBasic("beta/editor", "test-secret-123"))
                .with(csrf())).andExpect(status().isNotFound());

        // A composite tenant/field unique index allows the same business key in beta.
        mvc.perform(post(PATH).with(httpBasic("beta/editor", "test-secret-123"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        mvc.perform(put(PATH + "/" + id).with(httpBasic("alpha/editor", "test-secret-123"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(UPDATED_BODY))
                .andExpect(status().isOk());
        mvc.perform(delete(PATH + "/" + id).with(httpBasic("alpha/editor", "test-secret-123"))
                .with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(PATH + "/" + id).with(httpBasic("alpha/editor", "test-secret-123")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnauthenticatedForbiddenAndMissingCsrfWrites() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(httpBasic("alpha/editor", "test-secret-123"))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(httpBasic("alpha/reader", "test-secret-123"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isString());
    }

    @Test
    void changedBootstrapIdentityCannotCreateAnotherAdministrator() {
        BootstrapUser bootstrap = new BootstrapUser(users, encoder);
        ReflectionTestUtils.setField(bootstrap, "tenant", "gamma");
        ReflectionTestUtils.setField(bootstrap, "username", "other");
        ReflectionTestUtils.setField(bootstrap, "password", "test-secret-123");
        long before = users.count();
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.count()).isEqualTo(before);
    }
}
`;
}

export function renderBackend(m) {
  const p = m.project.packageName;
  const e = m.entity;
  const base = `backend/src/main/java/${p.replaceAll('.', '/')}`;
  const files = new Map([
    ['backend/pom.xml', pom(m)],
    ['backend/.gitignore', 'target/\n*.mv.db\n*.trace.db\n'],
    ['backend/.env.example', 'APP_BOOTSTRAP_TENANT=\nAPP_BOOTSTRAP_USERNAME=\nAPP_BOOTSTRAP_PASSWORD=\nAPP_PORT=8082\n'],
    ['backend/src/main/resources/application.properties', `server.port=\${APP_PORT:8082}\nspring.datasource.url=\${APP_DATASOURCE_URL:jdbc:h2:file:./appdb;DB_CLOSE_ON_EXIT=FALSE}\nspring.datasource.username=\${APP_DB_USER:sa}\nspring.datasource.password=\${APP_DB_PASSWORD:}\nspring.jpa.hibernate.ddl-auto=validate\nspring.jpa.open-in-view=false\nspring.flyway.enabled=true\nmanagement.endpoints.web.exposure.include=health,info\napp.bootstrap.tenant=\${APP_BOOTSTRAP_TENANT:}\napp.bootstrap.username=\${APP_BOOTSTRAP_USERNAME:}\napp.bootstrap.password=\${APP_BOOTSTRAP_PASSWORD:}\n`],
    ['backend/src/main/resources/db/migration/V1__initial.sql', migration(m)],
    [`${base}/Application.java`, `package ${p};\n\nimport org.springframework.boot.SpringApplication;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n\n@SpringBootApplication\npublic class Application {\n    public static void main(String[] args) { SpringApplication.run(Application.class, args); }\n}\n`],
    [`${base}/domain/${e.name}.java`, entityClass(m)],
    [`${base}/domain/${e.name}Repository.java`, repositoryClass(m)],
    [`${base}/domain/${e.name}Service.java`, serviceClass(m)],
    [`${base}/api/${e.name}Request.java`, requestClass(m)],
    [`${base}/api/${e.name}Response.java`, responseClass(m)],
    [`${base}/api/${e.name}Controller.java`, controllerClass(m)]
  ]);
  for (const [name, content] of securityClasses(m)) files.set(`${base}/${name}`, content);
  files.set(`backend/src/test/java/${p.replaceAll('.', '/')}/GeneratedApplicationTest.java`, integrationTest(m));
  return files;
}
