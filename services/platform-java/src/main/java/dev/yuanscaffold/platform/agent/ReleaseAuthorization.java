package dev.yuanscaffold.platform.agent;

import dev.yuanscaffold.platform.security.TenantPrincipal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A server-side Catalog decision; never deserialize this object from a user request. */
public record ReleaseAuthorization(
        UUID tenantId,
        UUID principalId,
        UUID runId,
        UUID threadId,
        TrustedReleaseCatalog.Operation operation,
        WorkflowRef workflowRef,
        String status,
        List<String> capabilities,
        List<ResourceGrant> grants
) {
    public ReleaseAuthorization {
        capabilities = List.copyOf(capabilities);
        grants = List.copyOf(grants);
    }

    void requireFor(TenantPrincipal principal, WorkflowRef requested, UUID requestedRunId,
                    UUID requestedThreadId, TrustedReleaseCatalog.Operation requestedOperation,
                    boolean requireActive,
                    boolean allowReadCapability) {
        if ((requireActive && !"ACTIVE".equals(status)) || !principal.tenantId().equals(tenantId)
                || !principal.userId().equals(principalId) || !requested.equals(workflowRef)
                || !requestedRunId.equals(runId) || !requestedThreadId.equals(threadId)
                || requestedOperation != operation) {
            throw new IllegalStateException("Release authorization does not match authenticated caller");
        }
        if (!(capabilities.contains("agent.run") || (allowReadCapability && capabilities.contains("agent.read")))
                || capabilities.size() != new HashSet<>(capabilities).size()
                || capabilities.stream().anyMatch(code -> !code.matches("[a-z][a-z0-9_.:-]{0,127}"))) {
            throw new IllegalStateException("Release authorization has invalid capabilities");
        }
        Set<String> groups = new HashSet<>();
        for (ResourceGrant grant : grants) {
            groups.add(grant.group());
        }
        if (grants.size() != 4 || groups.size() != 4
                || !groups.equals(Set.of("models", "prompts", "knowledge_bases", "indexes"))) {
            throw new IllegalStateException("Release authorization lacks exact resource grants");
        }
    }
}
