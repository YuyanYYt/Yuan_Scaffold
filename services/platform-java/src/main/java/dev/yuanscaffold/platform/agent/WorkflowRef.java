package dev.yuanscaffold.platform.agent;

import java.util.Map;
import java.util.TreeMap;

public record WorkflowRef(String id, String version, String semanticSha256) {
    public WorkflowRef {
        if (id == null || id.length() > 128
                || !id.matches("[a-z][a-z0-9_]*(?:[.-][a-z0-9_]+)*")) {
            throw new IllegalArgumentException("Invalid workflow ID");
        }
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException("Invalid workflow version");
        }
        if (semanticSha256 == null || !semanticSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid workflow semantic hash");
        }
    }

    Map<String, Object> wire() {
        Map<String, Object> map = new TreeMap<>();
        map.put("id", id);
        map.put("semantic_sha256", semanticSha256);
        map.put("version", version);
        return map;
    }
}
