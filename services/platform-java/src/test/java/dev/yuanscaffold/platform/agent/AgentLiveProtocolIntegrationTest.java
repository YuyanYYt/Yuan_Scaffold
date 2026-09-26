package dev.yuanscaffold.platform.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yuanscaffold.platform.security.TenantPrincipal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real loopback HTTP test; opt in after installing the Python package's locked test extra. */
@EnabledIfSystemProperty(named = "yuan.liveAgentApi", matches = "true")
class AgentLiveProtocolIntegrationTest {
    private static final UUID TENANT_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private static final UUID PRINCIPAL_ID = UUID.fromString("22345678-1234-4234-8234-123456789abc");
    private static final byte[] TEST_KEY = "test-only-cross-language-hmac-key-32!"
            .getBytes(StandardCharsets.UTF_8);
    private static final WorkflowRef WORKFLOW = new WorkflowRef("asset.faq", "0.1.0", "a".repeat(64));

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void defaultPythonFactoryRejectsSignedStartAndStatusWithoutCatalog() throws Exception {
        authenticate();
        try (PythonServer server = PythonServer.start("no-catalog")) {
            AgentInvocationClient client = client(server.uri());
            UUID runId = UUID.randomUUID();
            UUID threadId = UUID.randomUUID();
            AgentInvocationException start = assertThrows(AgentInvocationException.class,
                    () -> client.start(WORKFLOW, runId, threadId, UUID.randomUUID(), "live protocol probe"));
            assertEquals(503, start.status());
            assertEquals("RELEASE_CATALOG_UNAVAILABLE", start.code());
            AgentInvocationException status = assertThrows(AgentInvocationException.class,
                    () -> client.status(WORKFLOW, runId, threadId, UUID.randomUUID()));
            assertEquals(503, status.status());
            assertEquals("RELEASE_CATALOG_UNAVAILABLE", status.code());
        }
    }

    @Test
    void signedStartAndResumeRejectMissingExecutor() throws Exception {
        authenticate();
        try (PythonServer server = PythonServer.start("no-executor")) {
            AgentInvocationClient client = client(server.uri());
            UUID runId = UUID.randomUUID();
            UUID threadId = UUID.randomUUID();
            AgentInvocationException start = assertThrows(AgentInvocationException.class,
                    () -> client.start(WORKFLOW, runId, threadId, UUID.randomUUID(), "live protocol probe"));
            assertEquals(503, start.status());
            assertEquals("ADAPTER_UNAVAILABLE", start.code());
            AgentInvocationException resume = assertThrows(AgentInvocationException.class,
                    () -> client.resume(WORKFLOW, runId, threadId, UUID.randomUUID()));
            assertEquals(503, resume.status());
            assertEquals("ADAPTER_UNAVAILABLE", resume.code());
        }
    }

    @Test
    void testOnlyBackendSupportsStartStatusAndResumeAcrossRealHttp() throws Exception {
        authenticate();
        try (PythonServer server = PythonServer.start("test-backend")) {
            AgentInvocationClient client = client(server.uri());
            UUID runId = UUID.randomUUID();
            UUID threadId = UUID.randomUUID();
            AgentRunResult started = client.start(WORKFLOW, runId, threadId, UUID.randomUUID(),
                    "live protocol probe");
            assertEquals("PAUSED", started.status());
            assertNull(started.answer());
            assertEquals(3, started.usage().steps());

            AgentRunResult paused = client.status(WORKFLOW, runId, threadId, UUID.randomUUID());
            assertEquals("PAUSED", paused.status());
            assertEquals(runId, paused.traceId());

            AgentRunResult resumed = client.resume(WORKFLOW, runId, threadId, UUID.randomUUID());
            assertEquals("COMPLETED", resumed.status());
            assertEquals("TEST ONLY live resume", resumed.answer().text());
            assertEquals(List.of("c1"), resumed.answer().citationIds());

            AgentRunResult completed = client.status(WORKFLOW, runId, threadId, UUID.randomUUID());
            assertEquals("COMPLETED", completed.status());
            assertEquals(resumed.answer(), completed.answer());
        }
    }

    private static void authenticate() {
        TenantPrincipal principal = new TenantPrincipal(TENANT_ID, PRINCIPAL_ID, "alpha", "admin",
                "unused-test-hash", true, List.of("agent.run"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static AgentInvocationClient client(URI uri) {
        TrustedReleaseCatalog catalog = (principal, requested, runId, threadId, operation) ->
                new ReleaseAuthorization(principal.tenantId(), principal.userId(), runId, threadId,
                        operation, requested, "ACTIVE", List.of("agent.run"), List.of(
                        new ResourceGrant("models", "model.asset", "1.0.0"),
                        new ResourceGrant("prompts", "prompt.asset", "1.0.0"),
                        new ResourceGrant("knowledge_bases", "kb.asset", "1.0.0"),
                        new ResourceGrant("indexes", "index.asset", "1.0.0")));
        return new AgentInvocationClient(uri, "live-test-key", TEST_KEY, catalog, Clock.systemUTC());
    }

    private record PythonServer(Process process, URI uri, Path log) implements AutoCloseable {
        static PythonServer start(String mode) throws Exception {
            Path root = apiRoot();
            Path python = root.resolve(".venv/bin/python");
            if (!Files.isExecutable(python)) {
                throw new IllegalStateException("Run uv sync --locked --extra test in " + root + " first");
            }
            Path artifactParent = root.resolve("../platform-java/target/agent-live-protocol").normalize();
            Files.createDirectories(artifactParent);
            Path artifactDir = Files.createTempDirectory(artifactParent, mode + "-");
            Files.setPosixFilePermissions(artifactDir, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            Path ready = artifactDir.resolve("port");
            Path log = artifactDir.resolve("python.log");
            ProcessBuilder builder = new ProcessBuilder(
                    python.toString(), root.resolve("tests/live_protocol_server.py").toString(),
                    "--mode", mode, "--ledger", artifactDir.resolve("ledger.sqlite").toString(),
                    "--ready-file", ready.toString());
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            Process process = builder.start();
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                while (System.nanoTime() < deadline) {
                    if (Files.exists(ready)) {
                        String value = Files.readString(ready, StandardCharsets.US_ASCII).trim();
                        int port = Integer.parseInt(value);
                        assertTrue(port > 0 && port <= 65535);
                        return new PythonServer(process, URI.create("http://127.0.0.1:" + port), log);
                    }
                    if (!process.isAlive()) {
                        throw new IllegalStateException("Python test server exited: " + Files.readString(log));
                    }
                    Thread.sleep(50);
                }
                throw new IllegalStateException("Python test server did not become ready: " + Files.readString(log));
            } catch (Exception failure) {
                process.destroyForcibly();
                throw failure;
            }
        }

        private static Path apiRoot() {
            Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path sibling = current.resolve("../agent-api-python").normalize();
            if (Files.exists(sibling.resolve("tests/live_protocol_server.py"))) {
                return sibling;
            }
            Path nested = current.resolve("services/agent-api-python");
            if (Files.exists(nested.resolve("tests/live_protocol_server.py"))) {
                return nested;
            }
            throw new IllegalStateException("Cannot locate services/agent-api-python from " + current);
        }

        @Override
        public void close() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
            if (process.isAlive()) {
                throw new IllegalStateException("Python test server remains alive; inspect " + log);
            }
        }
    }
}
