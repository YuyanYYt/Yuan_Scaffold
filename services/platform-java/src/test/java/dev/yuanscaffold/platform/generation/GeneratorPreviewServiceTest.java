package dev.yuanscaffold.platform.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yuanscaffold.platform.generation.GeneratorPreviewService.PreviewException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Opt-in check against the real Node generator; regular Maven tests remain Java-only. */
@EnabledIfSystemProperty(named = "yuan.nodeGenerator", matches = "true")
class GeneratorPreviewServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void previewsGeneratedSourceAndReturnsExplicitManifestErrors() throws Exception {
        Path root = repoRoot();
        var service = new GeneratorPreviewService(mapper,
                root.resolve("packages/app-generator/src/cli.mjs").toString(), "node");
        JsonNode manifest = mapper.readTree(Files.readString(root.resolve("examples/asset-app/manifest.json")));
        JsonNode preview = service.preview(manifest);
        assertEquals("1.0.0", preview.path("previewSchemaVersion").asText());
        assertTrue(preview.path("totalBytes").asInt() > 0);
        assertTrue(preview.path("files").isArray());
        boolean controllerPresent = false;
        for (JsonNode file : preview.path("files")) {
            if (file.path("path").asText().endsWith("/AssetController.java")) {
                controllerPresent = true;
                assertTrue(file.path("content").asText().contains("/api/assets"));
                assertEquals(64, file.path("sha256").asText().length());
            }
        }
        assertTrue(controllerPresent);

        PreviewException invalid = assertThrows(PreviewException.class,
                () -> service.preview(mapper.readTree("{\"schemaVersion\":\"2.0.0\"}")));
        assertEquals(400, invalid.status());
        assertEquals("INVALID_MANIFEST", invalid.code());

        ObjectNode noisy = (ObjectNode) manifest.deepCopy();
        for (int index = 0; index < 600; index++) noisy.put("unsupported_" + index, index);
        PreviewException manyErrors = assertThrows(PreviewException.class, () -> service.preview(noisy));
        assertEquals(400, manyErrors.status());
        assertEquals("INVALID_MANIFEST", manyErrors.code());

        var unavailable = new GeneratorPreviewService(mapper, "", "node");
        PreviewException missing = assertThrows(PreviewException.class,
                () -> unavailable.preview(manifest));
        assertEquals(503, missing.status());
        assertEquals("GENERATOR_UNAVAILABLE", missing.code());
    }

    @Test
    void exportsIndependentSourceWithManifestAndSafeArchivePaths() throws Exception {
        Path root = repoRoot();
        var preview = new GeneratorPreviewService(mapper,
                root.resolve("packages/app-generator/src/cli.mjs").toString(), "node");
        var export = new CodeExportService(preview, mapper);
        JsonNode manifest = mapper.readTree(Files.readString(root.resolve("examples/asset-app/manifest.json")));
        byte[] archive = export.export(manifest);
        Map<String, String> files = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertTrue(!entry.getName().startsWith("/") && !entry.getName().contains(".."));
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertTrue(files.size() > 20);
        assertTrue(files.get("backend/pom.xml").contains("spring-boot-starter-parent"));
        assertTrue(files.get("web/src/AssetPage.tsx").contains("Asset"));
        assertEquals(manifest, mapper.readTree(files.get("yuan-manifest.json")));
        assertTrue(files.containsKey(".yuan-generation.json"));
    }

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path root = current.resolve("../..").normalize();
        if (Files.exists(root.resolve("packages/app-generator/src/cli.mjs"))) return root;
        if (Files.exists(current.resolve("packages/app-generator/src/cli.mjs"))) return current;
        throw new IllegalStateException("Cannot locate app-generator from " + current);
    }
}
