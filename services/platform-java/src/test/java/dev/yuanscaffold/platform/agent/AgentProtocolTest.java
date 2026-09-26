package dev.yuanscaffold.platform.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import dev.yuanscaffold.platform.security.TenantPrincipal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AgentProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void signerMatchesPythonFixedVectorExactly() throws Exception {
        JsonNode vector = mapper.readTree(Files.readString(vectorPath()));
        JsonNode assertion = mapper.readTree(vector.get("assertion_json_utf8").asText());
        UUID tenantId = UUID.fromString(assertion.get("tenant_id").asText());
        UUID principalId = UUID.fromString(assertion.get("principal_id").asText());
        TenantPrincipal principal = new TenantPrincipal(tenantId, principalId, "alpha", "admin",
                "unused-test-hash", true, List.of("agent.run"));
        JsonNode refNode = assertion.get("workflow_ref");
        WorkflowRef ref = new WorkflowRef(refNode.get("id").asText(), refNode.get("version").asText(),
                refNode.get("semantic_sha256").asText());
        List<ResourceGrant> grants = new ArrayList<>();
        assertion.get("grants").forEach(node -> grants.add(new ResourceGrant(
                node.get("group").asText(), node.get("resource_id").asText(),
                node.get("version").asText())));
        ReleaseAuthorization release = new ReleaseAuthorization(tenantId, principalId,
                UUID.fromString(assertion.get("run_id").asText()),
                UUID.fromString(assertion.get("thread_id").asText()),
                TrustedReleaseCatalog.Operation.START,
                ref, "ACTIVE", List.of("agent.run"), grants);
        byte[] body = vector.get("raw_body_utf8").asText().getBytes(StandardCharsets.UTF_8);
        HmacAssertionSigner signer = new HmacAssertionSigner(
                vector.get("key_id").asText(),
                vector.get("secret_utf8").asText().getBytes(StandardCharsets.UTF_8), mapper);
        var signed = signer.sign(vector.get("method").asText(), vector.get("path").asText(),
                body, principal, release,
                UUID.fromString(assertion.get("run_id").asText()),
                UUID.fromString(assertion.get("thread_id").asText()),
                UUID.fromString(assertion.get("request_id").asText()),
                UUID.fromString(assertion.get("jti").asText()),
                Instant.ofEpochSecond(assertion.get("iat").asLong()),
                Instant.ofEpochSecond(assertion.get("exp").asLong()),
                assertion.get("deadline_epoch_ms").asLong());
        assertEquals(vector.get("assertion_b64url").asText(), signed.assertionB64());
        assertEquals(vector.get("signature_b64url").asText(), signed.signatureB64());
        assertEquals("XL9P19hs8sYN2hmQ7_NidD6USw6EBOuoDp-FwxCBJX0", signed.signatureB64());
        assertEquals(vector.get("assertion_json_utf8").asText(),
                new String(Base64.getUrlDecoder().decode(signed.assertionB64()), StandardCharsets.UTF_8));
    }

    @Test
    void clientBindsPrincipalAndCatalogDecisionToActualHttpBody() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        TenantPrincipal principal = principal(tenantId, userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        WorkflowRef ref = workflowRef();
        ReleaseAuthorization release = release(tenantId, userId, ref, runId, threadId,
                TrustedReleaseCatalog.Operation.START);
        AtomicReference<String> observedTenant = new AtomicReference<>();
        AtomicReference<String> observedBodyHash = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/internal/v1/runs", exchange -> {
            try {
                calls.incrementAndGet();
                byte[] rawBody = exchange.getRequestBody().readAllBytes();
                String assertionB64 = exchange.getRequestHeaders().getFirst("X-Yuan-Assertion");
                String signatureB64 = exchange.getRequestHeaders().getFirst("X-Yuan-Signature");
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals("/internal/v1/runs", exchange.getRequestURI().getRawPath());
                assertEquals("vector-key-1", exchange.getRequestHeaders().getFirst("X-Yuan-Key-Id"));
                JsonNode assertion = mapper.readTree(Base64.getUrlDecoder().decode(assertionB64));
                observedTenant.set(assertion.get("tenant_id").asText());
                observedBodyHash.set(assertion.get("body_sha256").asText());
                assertEquals(userId.toString(), assertion.get("principal_id").asText());
                assertEquals(runId.toString(), assertion.get("run_id").asText());
                assertEquals(threadId.toString(), assertion.get("thread_id").asText());
                assertEquals(requestId.toString(), assertion.get("request_id").asText());
                assertEquals(sha256(rawBody), assertion.get("body_sha256").asText());
                assertEquals(signature(assertionB64), signatureB64);
                assertEquals("hello", mapper.readTree(rawBody).get("input").get("text").asText());
                String response = "{\"contract_version\":\"1.0.0\",\"run_id\":\"" + runId
                        + "\",\"workflow_ref\":{\"id\":\"asset.faq\",\"version\":\"0.1.0\",\"semantic_sha256\":\""
                        + "a".repeat(64) + "\"},\"status\":\"COMPLETED\",\"answer\":{\"status\":\"answered\",\"text\":\"synthetic\",\"citation_ids\":[\"c1\"]},"
                        + "\"usage\":{\"steps\":1,\"total_tokens\":2,\"cost_micro\":0,\"active_ms\":3,\"currency\":\"USD\"},"
                        + "\"trace_id\":\"" + runId + "\",\"stop_reason\":null}";
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                exchange.getResponseBody().write(responseBytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            TrustedReleaseCatalog catalog = (caller, requested, run, thread, operation) -> {
                assertEquals(tenantId, caller.tenantId());
                assertEquals(userId, caller.userId());
                assertEquals(ref, requested);
                assertEquals(TrustedReleaseCatalog.Operation.START, operation);
                return release;
            };
            AgentInvocationClient client = new AgentInvocationClient(uri, "vector-key-1",
                    "test-only-cross-language-hmac-key-32!".getBytes(StandardCharsets.UTF_8),
                    catalog, Clock.systemUTC());
            AgentRunResult result = client.start(ref, runId, threadId, requestId, "hello");
            assertEquals("COMPLETED", result.status());
            assertEquals("synthetic", result.answer().text());
            assertEquals(tenantId.toString(), observedTenant.get());
            assertNotNull(observedBodyHash.get());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingKeyOrMismatchedCatalogDecisionFailsBeforeNetwork() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        TenantPrincipal principal = principal(tenantId, userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        WorkflowRef ref = workflowRef();
        URI uri = URI.create("http://127.0.0.1:9");
        TrustedReleaseCatalog matching = (p, r, run, thread, operation) ->
                release(tenantId, userId, ref, run, thread, operation);
        assertThrows(IllegalArgumentException.class,
                () -> new AgentInvocationClient(uri, "key-1", null, matching, Clock.systemUTC()));
        assertThrows(NullPointerException.class,
                () -> new AgentInvocationClient(uri, "key-1", new byte[32], null, Clock.systemUTC()));
        TrustedReleaseCatalog wrongTenant = (p, r, run, thread, operation) ->
                release(UUID.randomUUID(), userId, ref, run, thread, operation);
        AgentInvocationClient client = new AgentInvocationClient(uri, "key-1", new byte[32],
                wrongTenant, Clock.systemUTC());
        assertThrows(IllegalStateException.class,
                () -> client.start(ref, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hello"));
        TrustedReleaseCatalog wrongRun = (p, r, run, thread, operation) ->
                release(tenantId, userId, ref, UUID.randomUUID(), thread, operation);
        AgentInvocationClient runBound = new AgentInvocationClient(uri, "key-1", new byte[32],
                wrongRun, Clock.systemUTC());
        assertThrows(IllegalStateException.class,
                () -> runBound.start(ref, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hello"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentInvocationClient(URI.create("http://example.com"), "key-1",
                        new byte[32], matching, Clock.systemUTC()));
    }

    @Test
    void statusAndResumeSignTheirExactPathsAndPreserveRequestIds() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID statusRequest = UUID.randomUUID();
        UUID resumeRequest = UUID.randomUUID();
        WorkflowRef ref = workflowRef();
        TenantPrincipal principal = principal(tenantId, userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/internal/v1/runs/", exchange -> {
            try {
                calls.incrementAndGet();
                String path = exchange.getRequestURI().getRawPath();
                byte[] rawBody = exchange.getRequestBody().readAllBytes();
                JsonNode assertion = mapper.readTree(Base64.getUrlDecoder().decode(
                        exchange.getRequestHeaders().getFirst("X-Yuan-Assertion")));
                assertEquals(path, assertion.get("path").asText());
                assertEquals(exchange.getRequestMethod(), assertion.get("method").asText());
                assertEquals(sha256(rawBody), assertion.get("body_sha256").asText());
                String status;
                String answer;
                if ("GET".equals(exchange.getRequestMethod())) {
                    assertEquals("/internal/v1/runs/" + runId, path);
                    assertEquals(0, rawBody.length);
                    assertEquals(statusRequest.toString(), assertion.get("request_id").asText());
                    status = "PAUSED";
                    answer = "null";
                } else {
                    assertEquals("/internal/v1/runs/" + runId + "/resume", path);
                    assertEquals(resumeRequest.toString(), assertion.get("request_id").asText());
                    assertEquals(resumeRequest.toString(), mapper.readTree(rawBody).get("request_id").asText());
                    status = "COMPLETED";
                    answer = "{\"status\":\"answered\",\"text\":\"resumed\",\"citation_ids\":[]}";
                }
                String response = "{\"contract_version\":\"1.0.0\",\"run_id\":\"" + runId
                        + "\",\"workflow_ref\":{\"id\":\"asset.faq\",\"version\":\"0.1.0\",\"semantic_sha256\":\""
                        + "a".repeat(64) + "\"},\"status\":\"" + status + "\",\"answer\":" + answer
                        + ",\"usage\":null,\"trace_id\":\"" + runId + "\",\"stop_reason\":null}";
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                exchange.getResponseBody().write(responseBytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            TrustedReleaseCatalog catalog = (caller, requested, run, thread, operation) -> {
                assertEquals(tenantId, caller.tenantId());
                assertEquals(ref, requested);
                assertEquals(runId, run);
                assertEquals(threadId, thread);
                return release(tenantId, userId, ref, run, thread, operation);
            };
            AgentInvocationClient client = new AgentInvocationClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "vector-key-1", "test-only-cross-language-hmac-key-32!".getBytes(StandardCharsets.UTF_8),
                    catalog, Clock.systemUTC());
            AgentRunResult paused = client.status(ref, runId, threadId, statusRequest);
            assertEquals("PAUSED", paused.status());
            AgentRunResult resumed = client.resume(ref, runId, threadId, resumeRequest);
            assertEquals("COMPLETED", resumed.status());
            assertEquals("resumed", resumed.answer().text());
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    private static Path vectorPath() {
        Path module = Path.of(System.getProperty("user.dir"));
        Path sibling = module.resolve("../agent-api-python/tests/vectors/hmac-v1.json").normalize();
        if (Files.exists(sibling)) {
            return sibling;
        }
        return module.resolve("services/agent-api-python/tests/vectors/hmac-v1.json");
    }

    private static TenantPrincipal principal(UUID tenantId, UUID userId) {
        return new TenantPrincipal(tenantId, userId, "alpha", "admin", "unused-test-hash",
                true, List.of("agent.run"));
    }

    private static WorkflowRef workflowRef() {
        return new WorkflowRef("asset.faq", "0.1.0", "a".repeat(64));
    }

    private static ReleaseAuthorization release(UUID tenantId, UUID userId, WorkflowRef ref,
                                                UUID runId, UUID threadId,
                                                TrustedReleaseCatalog.Operation operation) {
        return new ReleaseAuthorization(tenantId, userId, runId, threadId, operation,
                ref, "ACTIVE", List.of("agent.run"), List.of(
                new ResourceGrant("models", "model.asset", "1.0.0"),
                new ResourceGrant("prompts", "prompt.asset", "1.0.0"),
                new ResourceGrant("knowledge_bases", "kb.asset", "1.0.0"),
                new ResourceGrant("indexes", "index.asset", "1.0.0")));
    }

    private static String sha256(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String signature(String assertionB64) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-only-cross-language-hmac-key-32!".getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] signed = mac.doFinal(("YUAN-HMAC-V1\n" + assertionB64).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signed);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
