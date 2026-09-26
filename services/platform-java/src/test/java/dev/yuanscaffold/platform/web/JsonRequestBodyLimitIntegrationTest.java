package dev.yuanscaffold.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:json-request-body-limit;DB_CLOSE_DELAY=-1",
        "platform.bootstrap.tenant-slug=body-limit-alpha",
        "platform.bootstrap.username=admin",
        "platform.bootstrap.password=SyntheticBodyLimitPassword123"
})
@AutoConfigureMockMvc
class JsonRequestBodyLimitIntegrationTest {
    private static final String LOGIN = "body-limit-alpha/admin";
    private static final String PASSWORD = "SyntheticBodyLimitPassword123";
    private static final int MAX = JsonRequestBodyLimitFilter.MAX_BODY_BYTES;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void declaredOversizeIsRejectedBeforeJsonParsingOrMutation() throws Exception {
        long before = roleCount();
        mvc.perform(requestWithReportedLength("application/json",
                        "{not json".getBytes(StandardCharsets.UTF_8), MAX + 1))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_TOO_LARGE"))
                .andExpect(jsonPath("$.timestamp").exists());
        assertEquals(before, roleCount());
    }

    @Test
    void unknownLengthChunkedBodyIsRejectedByActualBytes() throws Exception {
        long before = roleCount();
        byte[] body = ("{\"code\":\"oversized_role\",\"name\":\""
                + "a".repeat(MAX) + "\"}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(requestWithReportedLength("application/vnd.yuan+json", body, -1))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_TOO_LARGE"));
        assertEquals(before, roleCount());
    }

    @Test
    void falselySmallLengthStillCannotBypassActualByteLimit() throws Exception {
        byte[] body = ("{\"code\":\"oversized_role\",\"name\":\""
                + "b".repeat(MAX) + "\"}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(requestWithReportedLength("application/json", body, 2))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_TOO_LARGE"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE code = ?", Long.class,
                "oversized_role"));
    }

    @Test
    void exactByteBoundaryReachesMvcAndKeepsNormalWritesWorking() throws Exception {
        String json = "{\"code\":\"boundary_role\",\"name\":\"Boundary role\"}";
        byte[] body = (json + " ".repeat(MAX - json.length())).getBytes(StandardCharsets.UTF_8);
        assertEquals(MAX, body.length);
        mvc.perform(authenticatedPost("/api/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("boundary_role"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE code = ?", Long.class,
                "boundary_role"));
    }

    @Test
    void authenticationAndCsrfStillTakePrecedenceOverBodyLimit() throws Exception {
        byte[] body = ("{" + "x".repeat(MAX) + "}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/api/v1/roles")
                        .with(httpBasic(LOGIN, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        var tokenResult = mvc.perform(get("/api/v1/csrf").with(httpBasic(LOGIN, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(tokenResult.getResponse().getContentAsString()).get("token").asText();
        Cookie cookie = tokenResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        mvc.perform(post("/api/v1/roles")
                        .cookie(cookie).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private long roleCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM roles", Long.class);
    }

    private MockHttpServletRequestBuilder authenticatedPost(String path) throws Exception {
        var tokenResult = mvc.perform(get("/api/v1/csrf").with(httpBasic(LOGIN, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(tokenResult.getResponse().getContentAsString()).get("token").asText();
        Cookie cookie = tokenResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        return post(path).with(httpBasic(LOGIN, PASSWORD))
                .cookie(cookie).header("X-XSRF-TOKEN", token);
    }

    private RequestBuilder requestWithReportedLength(String contentType, byte[] body,
                                                      long reportedLength) throws Exception {
        var tokenResult = mvc.perform(get("/api/v1/csrf").with(httpBasic(LOGIN, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(tokenResult.getResponse().getContentAsString()).get("token").asText();
        Cookie cookie = tokenResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (LOGIN + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        return servletContext -> {
            MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "POST", "/api/v1/roles") {
                @Override
                public int getContentLength() {
                    return (int) reportedLength;
                }

                @Override
                public long getContentLengthLong() {
                    return reportedLength;
                }
            };
            request.setServletPath("/api/v1/roles");
            request.setContentType(contentType);
            request.setContent(body);
            request.setCookies(cookie);
            request.addHeader("Authorization", authorization);
            request.addHeader("X-XSRF-TOKEN", token);
            if (reportedLength < 0) {
                request.addHeader("Transfer-Encoding", "chunked");
            } else {
                request.addHeader("Content-Length", reportedLength);
            }
            assertEquals(reportedLength, request.getContentLengthLong());
            return request;
        };
    }
}
