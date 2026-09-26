package dev.yuanscaffold.platform.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yuanscaffold.platform.generation.WorkflowDraftValidationService.ValidationException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/** Opt-in check that Java invokes the actual server-side graph converter. */
@EnabledIfSystemProperty(named = "yuan.nodeStudio", matches = "true")
class WorkflowDraftValidationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesDraftThroughNodeAndNeverClaimsExecutableOrPublishable() throws Exception {
        Path root = repoRoot();
        var service = new WorkflowDraftValidationService(mapper,
                root.resolve("packages/studio-graph/src/cli.mjs").toString(), "node");
        ObjectNode graph = (ObjectNode) mapper.readTree(Files.readString(
                root.resolve("apps/platform-admin/src/workflow-starter.json")));

        JsonNode valid = service.validate(graph);
        assertTrue(valid.path("valid").asBoolean());
        assertEquals("draft_static_only", valid.path("scope").asText());
        assertFalse(valid.path("publishable").asBoolean());
        assertFalse(valid.path("executable").asBoolean());
        assertEquals("DRAFT", valid.path("ir").path("status").asText());
        assertEquals("sample.default_qa", valid.path("ir").path("workflow_id").asText());

        ((ObjectNode) graph.path("edges").get(0)).put("sourceHandle", "unknown_port");
        JsonNode invalid = service.validate(graph);
        assertFalse(invalid.path("valid").asBoolean());
        assertTrue(invalid.path("ir").isNull());
        assertTrue(invalid.path("errors").size() > 0);

        ObjectNode oversizedGraph = (ObjectNode) mapper.readTree(Files.readString(
                root.resolve("apps/platform-admin/src/workflow-starter.json")));
        ArrayNode manyNodes = mapper.createArrayNode();
        for (int index = 0; index < 50_000; index++) manyNodes.addNull();
        oversizedGraph.set("nodes", manyNodes);
        JsonNode oversized = service.validate(oversizedGraph);
        assertFalse(oversized.path("valid").asBoolean());
        assertEquals("UI_SIZE", oversized.path("errors").get(0).path("code").asText());

        var unavailable = new WorkflowDraftValidationService(mapper, "", "node");
        ValidationException missing = assertThrows(ValidationException.class,
                () -> unavailable.validate(graph));
        assertEquals(503, missing.status());
        assertEquals("STUDIO_GRAPH_UNAVAILABLE", missing.code());
    }

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path root = current.resolve("../..").normalize();
        if (Files.exists(root.resolve("packages/studio-graph/src/cli.mjs"))) return root;
        if (Files.exists(current.resolve("packages/studio-graph/src/cli.mjs"))) return current;
        throw new IllegalStateException("Cannot locate studio-graph from " + current);
    }
}
