package dev.yuanscaffold.platform.agent;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public record ResourceGrant(String group, String resourceId, String version) {
    private static final Set<String> GROUPS = Set.of("models", "prompts", "knowledge_bases", "indexes");

    public ResourceGrant {
        if (!GROUPS.contains(group)) {
            throw new IllegalArgumentException("Unknown resource group");
        }
        if (resourceId == null || resourceId.length() > 128
                || !resourceId.matches("[a-z][a-z0-9_]*(?:[.-][a-z0-9_]+)*")) {
            throw new IllegalArgumentException("Invalid resource ID");
        }
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException("Invalid resource version");
        }
    }

    Map<String, Object> wire() {
        Map<String, Object> map = new TreeMap<>();
        map.put("group", group);
        map.put("resource_id", resourceId);
        map.put("version", version);
        return map;
    }
}
