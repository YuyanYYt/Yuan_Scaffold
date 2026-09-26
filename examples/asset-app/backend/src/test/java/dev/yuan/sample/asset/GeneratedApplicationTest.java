package dev.yuan.sample.asset;

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
import dev.yuan.sample.asset.domain.AssetRepository;
import dev.yuan.sample.asset.security.AppUser;
import dev.yuan.sample.asset.security.AppUserRepository;
import dev.yuan.sample.asset.security.BootstrapUser;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:generatedtest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GeneratedApplicationTest {
    private static final String PATH = "/api/assets";
    private static final String BODY = "{\"sku\":\"sample-sku\",\"name\":\"sample-name\",\"quantity\":5,\"available\":true}";
    private static final String UPDATED_BODY = "{\"sku\":\"updated-sku\",\"name\":\"sample-name\",\"quantity\":5,\"available\":true}";

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired AssetRepository records;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        records.deleteAll();
        users.deleteAll();
        users.save(new AppUser("alpha/editor", "alpha", encoder.encode("test-secret-123"), "asset:read,asset:write"));
        users.save(new AppUser("beta/editor", "beta", encoder.encode("test-secret-123"), "asset:read,asset:write"));
        users.save(new AppUser("alpha/reader", "alpha", encoder.encode("test-secret-123"), "asset:read"));
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
