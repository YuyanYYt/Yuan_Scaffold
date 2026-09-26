package dev.yuanscaffold.platform.agent;

import dev.yuanscaffold.platform.agent.HmacAssertionSigner.SignedRequest;
import dev.yuanscaffold.platform.security.AuthContext;
import dev.yuanscaffold.platform.security.TenantPrincipal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal Java-to-Python client. No Spring bean is registered until a real key and trusted
 * Release Catalog are supplied by the host application.
 */
public final class AgentInvocationClient {
    private static final String VERSION = "1.0.0";
    private static final String START_PATH = "/internal/v1/runs";
    private static final Set<String> STATUSES = Set.of("IN_PROGRESS", "COMPLETED", "PAUSED", "STOPPED", "FAILED");

    private final RestClient http;
    private final HmacAssertionSigner signer;
    private final TrustedReleaseCatalog catalog;
    private final Clock clock;
    private final ObjectMapper mapper;

    public AgentInvocationClient(URI baseUri, String keyId, byte[] key,
                                 TrustedReleaseCatalog catalog, Clock clock) {
        validateBaseUri(baseUri);
        this.catalog = Objects.requireNonNull(catalog, "Trusted Release Catalog is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.mapper = new ObjectMapper();
        this.signer = new HmacAssertionSigner(keyId, key, mapper);
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(35));
        this.http = RestClient.builder().baseUrl(baseUri.toString())
                .requestFactory(requestFactory).build();
    }

    /** IDs are server-owned and must be persisted by the caller for idempotent retries. */
    public AgentRunResult start(WorkflowRef workflowRef, UUID runId, UUID threadId,
                                UUID requestId, String inputText) {
        Objects.requireNonNull(workflowRef);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(threadId);
        Objects.requireNonNull(requestId);
        if (inputText == null || inputText.isBlank() || inputText.length() > 16000) {
            throw new IllegalArgumentException("Input text must contain 1 to 16000 characters");
        }
        byte[] body = json(startBody(workflowRef, runId, threadId, requestId, inputText));
        return invoke("POST", START_PATH, body, workflowRef, runId, threadId, requestId,
                TrustedReleaseCatalog.Operation.START);
    }

    public AgentRunResult status(WorkflowRef workflowRef, UUID runId, UUID threadId,
                                 UUID requestId) {
        String path = "/internal/v1/runs/" + Objects.requireNonNull(runId);
        return invoke("GET", path, new byte[0], workflowRef, runId, threadId, requestId,
                TrustedReleaseCatalog.Operation.STATUS);
    }

    /** Reuse requestId across retries of the same resume command. */
    public AgentRunResult resume(WorkflowRef workflowRef, UUID runId, UUID threadId,
                                 UUID requestId) {
        String path = "/internal/v1/runs/" + Objects.requireNonNull(runId) + "/resume";
        Map<String, Object> body = new TreeMap<>();
        body.put("contract_version", VERSION);
        body.put("request_id", Objects.requireNonNull(requestId).toString());
        return invoke("POST", path, json(body), workflowRef, runId, threadId, requestId,
                TrustedReleaseCatalog.Operation.RESUME);
    }

    private AgentRunResult invoke(String method, String path, byte[] body, WorkflowRef workflowRef,
                                  UUID runId, UUID threadId, UUID requestId,
                                  TrustedReleaseCatalog.Operation operation) {
        Objects.requireNonNull(workflowRef);
        Objects.requireNonNull(threadId);
        Objects.requireNonNull(requestId);
        TenantPrincipal principal = AuthContext.current();
        ReleaseAuthorization release = catalog.authorize(principal, workflowRef, runId, threadId, operation);
        if (release == null) {
            throw new IllegalStateException("Release Catalog returned no authorization");
        }
        boolean statusRequest = operation == TrustedReleaseCatalog.Operation.STATUS;
        release.requireFor(principal, workflowRef, runId, threadId, operation,
                !statusRequest, statusRequest);
        Instant issuedAt = clock.instant();
        SignedRequest signed = signer.sign(method, path, body, principal, release,
                runId, threadId, requestId, UUID.randomUUID(), issuedAt,
                issuedAt.plusSeconds(45), issuedAt.plusSeconds(30).toEpochMilli());
        try {
            String raw;
            if ("GET".equals(method)) {
                raw = http.get().uri(path)
                        .headers(headers -> addHeaders(headers, signed))
                        .retrieve().body(String.class);
            } else {
                raw = http.post().uri(path)
                        .headers(headers -> addHeaders(headers, signed))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(signed.rawBody())
                        .retrieve().body(String.class);
            }
            return parseResponse(raw, runId, workflowRef);
        } catch (RestClientResponseException exception) {
            throw new AgentInvocationException(exception.getStatusCode().value(), remoteCode(exception));
        } catch (RestClientException exception) {
            throw new AgentInvocationException(502, "TRANSPORT_FAILED");
        }
    }

    private static void addHeaders(org.springframework.http.HttpHeaders headers, SignedRequest signed) {
        headers.set("X-Yuan-Key-Id", signed.keyId());
        headers.set("X-Yuan-Assertion", signed.assertionB64());
        headers.set("X-Yuan-Signature", signed.signatureB64());
    }

    private Map<String, Object> startBody(WorkflowRef ref, UUID runId, UUID threadId,
                                          UUID requestId, String inputText) {
        Map<String, Object> input = new TreeMap<>();
        input.put("text", inputText);
        Map<String, Object> body = new TreeMap<>();
        body.put("contract_version", VERSION);
        body.put("input", input);
        body.put("request_id", requestId.toString());
        body.put("run_id", runId.toString());
        body.put("thread_id", threadId.toString());
        body.put("workflow_ref", ref.wire());
        return body;
    }

    private byte[] json(Map<String, Object> map) {
        return mapper.writeValueAsBytes(map);
    }

    private AgentRunResult parseResponse(String raw, UUID runId, WorkflowRef ref) {
        try {
            if (raw == null || raw.length() > 65536) {
                throw new IllegalArgumentException();
            }
            JsonNode node = mapper.readTree(raw);
            if (!VERSION.equals(text(node, "contract_version"))
                    || !runId.toString().equals(text(node, "run_id"))
                    || !ref.id().equals(text(node.get("workflow_ref"), "id"))
                    || !ref.version().equals(text(node.get("workflow_ref"), "version"))
                    || !ref.semanticSha256().equals(text(node.get("workflow_ref"), "semantic_sha256"))) {
                throw new IllegalArgumentException();
            }
            String status = text(node, "status");
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException();
            }
            JsonNode answerNode = node.get("answer");
            AgentRunResult.Answer answer = null;
            if (answerNode != null && !answerNode.isNull()) {
                List<String> citations = new ArrayList<>();
                JsonNode ids = answerNode.get("citation_ids");
                if (ids == null || !ids.isArray()) {
                    throw new IllegalArgumentException();
                }
                ids.forEach(id -> citations.add(id.asText()));
                answer = new AgentRunResult.Answer(text(answerNode, "status"),
                        text(answerNode, "text"), List.copyOf(citations));
            }
            if ("COMPLETED".equals(status) != (answer != null)) {
                throw new IllegalArgumentException();
            }
            JsonNode usageNode = node.get("usage");
            AgentRunResult.Usage usage = null;
            if (usageNode != null && !usageNode.isNull()) {
                usage = new AgentRunResult.Usage(number(usageNode, "steps"),
                        number(usageNode, "total_tokens"), number(usageNode, "cost_micro"),
                        number(usageNode, "active_ms"), text(usageNode, "currency"));
            }
            JsonNode reason = node.get("stop_reason");
            return new AgentRunResult(runId, ref, status, answer, usage,
                    UUID.fromString(text(node, "trace_id")),
                    reason == null || reason.isNull() ? null : reason.asText());
        } catch (RuntimeException exception) {
            throw new AgentInvocationException(502, "INVALID_REMOTE_RESPONSE");
        }
    }

    private String remoteCode(RestClientResponseException exception) {
        try {
            String raw = exception.getResponseBodyAsString();
            if (raw.length() > 4096) {
                return "REMOTE_ERROR";
            }
            JsonNode error = mapper.readTree(raw).get("error");
            String code = text(error, "code");
            return code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "REMOTE_ERROR";
        } catch (RuntimeException ignored) {
            return "REMOTE_ERROR";
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Missing protocol field");
        }
        return value.asText();
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() < 0) {
            throw new IllegalArgumentException("Invalid protocol number");
        }
        return value.asLong();
    }

    private static void validateBaseUri(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Internal Agent API base URI must be an origin");
        }
        if (!"https".equals(uri.getScheme())
                && !("http".equals(uri.getScheme())
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())))) {
            throw new IllegalArgumentException("Internal Agent API requires HTTPS outside loopback");
        }
    }
}
