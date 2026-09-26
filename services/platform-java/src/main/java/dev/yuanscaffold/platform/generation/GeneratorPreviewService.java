package dev.yuanscaffold.platform.generation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class GeneratorPreviewService {
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_STDOUT_BYTES = 8 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final String cliPath;
    private final String nodeBin;
    private final Semaphore slots = new Semaphore(2);

    public GeneratorPreviewService(ObjectMapper mapper,
            @Value("${PLATFORM_GENERATOR_CLI:}") String cliPath,
            @Value("${PLATFORM_NODE_BIN:node}") String nodeBin) {
        this.mapper = mapper;
        this.cliPath = cliPath;
        this.nodeBin = nodeBin;
    }

    public JsonNode preview(JsonNode manifest) {
        if (manifest == null || !manifest.isObject()) {
            throw new PreviewException(400, "INVALID_MANIFEST", "Manifest must be a JSON object");
        }
        byte[] input = mapper.writeValueAsBytes(manifest);
        if (input.length > MAX_MANIFEST_BYTES) {
            throw new PreviewException(413, "MANIFEST_TOO_LARGE", "Manifest exceeds 256 KiB");
        }
        if (cliPath.isBlank() || !Files.isRegularFile(Path.of(cliPath))) {
            throw new PreviewException(503, "GENERATOR_UNAVAILABLE", "Source preview is not configured");
        }
        if (!slots.tryAcquire()) {
            throw new PreviewException(429, "PREVIEW_BUSY", "Source preview is at capacity");
        }
        try {
            NodeCliProcess.Result result;
            try {
                result = NodeCliProcess.run(nodeBin, cliPath, "preview", input, MAX_STDOUT_BYTES);
            } catch (NodeCliProcess.Failure failure) {
                throw switch (failure.reason()) {
                    case UNAVAILABLE -> new PreviewException(503, "GENERATOR_UNAVAILABLE", "Source preview could not start");
                    case TIMEOUT -> new PreviewException(504, "GENERATOR_TIMEOUT", "Source preview timed out");
                    case FAILED -> new PreviewException(503, "GENERATOR_FAILED", "Source preview did not complete");
                };
            }
            if (result.exitCode() != 0) {
                if (result.stderr().startsWith("invalid manifest:")) {
                    throw new PreviewException(400, "INVALID_MANIFEST", result.stderr().strip());
                }
                throw new PreviewException(503, "GENERATOR_FAILED", "Source preview failed");
            }
            JsonNode output = mapper.readTree(result.stdout());
            if (!output.isObject() || !output.path("files").isArray()
                    || !"1.0.0".equals(output.path("previewSchemaVersion").asText())) {
                throw new PreviewException(503, "GENERATOR_FAILED", "Source preview returned an invalid result");
            }
            return output;
        } catch (PreviewException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PreviewException(503, "GENERATOR_FAILED", "Source preview returned invalid JSON");
        } finally {
            slots.release();
        }
    }

    public static final class PreviewException extends RuntimeException {
        private final int status;
        private final String code;

        private PreviewException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }

        public String code() { return code; }
    }
}
