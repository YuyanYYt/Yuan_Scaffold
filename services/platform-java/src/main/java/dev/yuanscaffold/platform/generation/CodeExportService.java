package dev.yuanscaffold.platform.generation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CodeExportService {
    private static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;

    private final GeneratorPreviewService preview;
    private final ObjectMapper mapper;

    public CodeExportService(GeneratorPreviewService preview, ObjectMapper mapper) {
        this.preview = preview;
        this.mapper = mapper;
    }

    public byte[] export(JsonNode manifest) {
        JsonNode plan = preview.preview(manifest);
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            Set<String> seen = new HashSet<>();
            int sourceBytes = 0;
            for (JsonNode file : plan.path("files")) {
                String path = file.path("path").asText("");
                if (!safePath(path) || "yuan-manifest.json".equals(path) || !seen.add(path)
                        || !file.path("content").isTextual()) {
                    throw new ExportException("Generator returned an unsafe file");
                }
                byte[] content = file.path("content").asText().getBytes(StandardCharsets.UTF_8);
                sourceBytes += content.length;
                if (sourceBytes > MAX_SOURCE_BYTES) throw new ExportException("Generated source exceeds the export limit");
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
                if (!hash.equals(file.path("sha256").asText()) || content.length != file.path("bytes").asInt()) {
                    throw new ExportException("Generator file checksum does not match preview");
                }
                put(zip, path, content);
            }
            if (sourceBytes != plan.path("totalBytes").asInt(-1)) {
                throw new ExportException("Generator source size does not match preview");
            }
            put(zip, "yuan-manifest.json", (mapper.writeValueAsString(manifest) + "\n").getBytes(StandardCharsets.UTF_8));
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ExportException("Source export could not be assembled");
        }
    }

    private static boolean safePath(String path) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\")) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || !segment.matches("[A-Za-z0-9._-]+")) return false;
        }
        return true;
    }

    private static void put(ZipOutputStream zip, String path, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    public static final class ExportException extends RuntimeException {
        private ExportException(String message) { super(message); }
    }
}
