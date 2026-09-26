package dev.yuanscaffold.platform.agent;

import dev.yuanscaffold.platform.security.TenantPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;

/** Package-private so only the trusted internal client can emit service assertions. */
final class HmacAssertionSigner {
    private static final byte[] PREFIX = "YUAN-HMAC-V1\n".getBytes(StandardCharsets.US_ASCII);
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private final String keyId;
    private final byte[] key;
    private final ObjectMapper mapper;

    HmacAssertionSigner(String keyId, byte[] key, ObjectMapper mapper) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("A valid HMAC key ID is required");
        }
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("HMAC key must contain at least 32 secret bytes");
        }
        this.keyId = keyId;
        this.key = key.clone();
        this.mapper = mapper;
    }

    SignedRequest sign(String method, String path, byte[] rawBody,
                       TenantPrincipal principal, ReleaseAuthorization release,
                       UUID runId, UUID threadId, UUID requestId,
                       UUID jti, Instant issuedAt, Instant expiresAt, long deadlineEpochMs) {
        boolean start = "POST".equals(method) && "/internal/v1/runs".equals(path);
        boolean resume = "POST".equals(method)
                && path.matches("/internal/v1/runs/[0-9a-f-]{36}/resume");
        boolean status = "GET".equals(method)
                && path.matches("/internal/v1/runs/[0-9a-f-]{36}");
        if (!start && !resume && !status) {
            throw new IllegalArgumentException("Unsupported internal endpoint");
        }
        TrustedReleaseCatalog.Operation operation = status ? TrustedReleaseCatalog.Operation.STATUS
                : (start ? TrustedReleaseCatalog.Operation.START : TrustedReleaseCatalog.Operation.RESUME);
        release.requireFor(principal, release.workflowRef(), runId, threadId, operation, !status, status);
        if (rawBody == null || rawBody.length > 65536
                || (status && rawBody.length != 0) || (!status && rawBody.length == 0)) {
            throw new IllegalArgumentException("Invalid raw body size");
        }
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)
                || expiresAt.getEpochSecond() - issuedAt.getEpochSecond() > 60
                || deadlineEpochMs <= issuedAt.toEpochMilli()
                || deadlineEpochMs > expiresAt.getEpochSecond() * 1000) {
            throw new IllegalArgumentException("Invalid assertion lifetime");
        }
        Map<String, Object> assertion = new TreeMap<>();
        assertion.put("assertion_version", "1.0.0");
        assertion.put("aud", "yuan-agent-api-python");
        assertion.put("body_sha256", sha256(rawBody));
        assertion.put("capabilities", release.capabilities());
        assertion.put("deadline_epoch_ms", deadlineEpochMs);
        assertion.put("exp", expiresAt.getEpochSecond());
        List<Map<String, Object>> grants = release.grants().stream().map(ResourceGrant::wire).toList();
        assertion.put("grants", grants);
        assertion.put("iat", issuedAt.getEpochSecond());
        assertion.put("iss", "yuan-platform-java");
        assertion.put("jti", jti.toString());
        assertion.put("method", method);
        assertion.put("path", path);
        assertion.put("principal_id", principal.userId().toString());
        assertion.put("request_id", requestId.toString());
        assertion.put("run_id", runId.toString());
        assertion.put("tenant_id", principal.tenantId().toString());
        assertion.put("thread_id", threadId.toString());
        assertion.put("workflow_ref", release.workflowRef().wire());

        byte[] assertionJson = mapper.writeValueAsBytes(assertion);
        String assertionB64 = BASE64.encodeToString(assertionJson);
        if (assertionB64.length() > 16384) {
            throw new IllegalArgumentException("Assertion is too large");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(PREFIX);
            mac.update(assertionB64.getBytes(StandardCharsets.US_ASCII));
            return new SignedRequest(keyId, assertionB64, BASE64.encodeToString(mac.doFinal()), rawBody.clone());
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC signing is unavailable", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record SignedRequest(String keyId, String assertionB64, String signatureB64, byte[] rawBody) {
        SignedRequest {
            rawBody = rawBody.clone();
        }

        @Override
        public byte[] rawBody() {
            return rawBody.clone();
        }
    }
}
