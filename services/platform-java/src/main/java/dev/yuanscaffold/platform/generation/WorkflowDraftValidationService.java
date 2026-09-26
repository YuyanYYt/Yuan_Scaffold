package dev.yuanscaffold.platform.generation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class WorkflowDraftValidationService {
    private static final int MAX_GRAPH_BYTES = 1024 * 1024;
    private static final int MAX_STDOUT_BYTES = 3 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final String cliPath;
    private final String nodeBin;
    private final Semaphore slots = new Semaphore(2);

    public WorkflowDraftValidationService(ObjectMapper mapper,
            @Value("${PLATFORM_STUDIO_GRAPH_CLI:}") String cliPath,
            @Value("${PLATFORM_NODE_BIN:node}") String nodeBin) {
        this.mapper = mapper;
        this.cliPath = cliPath;
        this.nodeBin = nodeBin;
    }

    public JsonNode validate(JsonNode graph) {
        if (graph == null || !graph.isObject()) {
            throw new ValidationException(400, "INVALID_GRAPH", "Studio graph must be a JSON object");
        }
        byte[] input = mapper.writeValueAsBytes(graph);
        if (input.length > MAX_GRAPH_BYTES) {
            throw new ValidationException(413, "GRAPH_TOO_LARGE", "Studio graph exceeds 1 MiB");
        }
        if (cliPath.isBlank() || !Files.isRegularFile(Path.of(cliPath))) {
            throw new ValidationException(503, "STUDIO_GRAPH_UNAVAILABLE", "Workflow validation is not configured");
        }
        if (!slots.tryAcquire()) {
            throw new ValidationException(429, "VALIDATION_BUSY", "Workflow validation is at capacity");
        }
        try {
            NodeCliProcess.Result result;
            try {
                result = NodeCliProcess.run(nodeBin, cliPath, "convert", input, MAX_STDOUT_BYTES);
            } catch (NodeCliProcess.Failure failure) {
                throw switch (failure.reason()) {
                    case UNAVAILABLE -> new ValidationException(503, "STUDIO_GRAPH_UNAVAILABLE", "Workflow validator could not start");
                    case TIMEOUT -> new ValidationException(504, "VALIDATION_TIMEOUT", "Workflow validation timed out");
                    case FAILED -> new ValidationException(503, "VALIDATION_FAILED", "Workflow validation did not complete");
                };
            }
            if (result.exitCode() != 0) {
                throw new ValidationException(503, "VALIDATION_FAILED", "Workflow validator failed");
            }
            JsonNode output = mapper.readTree(result.stdout());
            if (!output.isObject() || !output.path("valid").isBoolean()
                    || !"draft_static_only".equals(output.path("scope").asText())
                    || !output.path("publishable").isBoolean() || output.path("publishable").asBoolean()
                    || !output.path("executable").isBoolean() || output.path("executable").asBoolean()
                    || !output.path("errors").isArray()
                    || (output.path("valid").asBoolean() && !"DRAFT".equals(output.path("ir").path("status").asText()))
                    || (!output.path("valid").asBoolean() && !output.path("ir").isNull())) {
                throw new ValidationException(503, "VALIDATION_FAILED", "Workflow validator returned an invalid result");
            }
            return output;
        } catch (ValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ValidationException(503, "VALIDATION_FAILED", "Workflow validator returned invalid JSON");
        } finally {
            slots.release();
        }
    }

    public static final class ValidationException extends RuntimeException {
        private final int status;
        private final String code;

        private ValidationException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }

        public String code() { return code; }
    }
}
