package dev.yuanscaffold.platform.agent;

import java.util.List;
import java.util.UUID;

public record AgentRunResult(
        UUID runId,
        WorkflowRef workflowRef,
        String status,
        Answer answer,
        Usage usage,
        UUID traceId,
        String stopReason
) {
    public record Answer(String status, String text, List<String> citationIds) { }
    public record Usage(long steps, long totalTokens, long costMicro, long activeMs,
                        String currency) { }
}
