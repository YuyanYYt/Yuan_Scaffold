package dev.yuanscaffold.platform.generation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class NodeCliProcess {
    private static final int MAX_STDERR_BYTES = 16 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private NodeCliProcess() { }

    static Result run(String nodeBin, String cliPath, String operation, byte[] input, int maxStdoutBytes) {
        Process process;
        try {
            process = new ProcessBuilder(nodeBin, cliPath, operation, "-").start();
        } catch (IOException exception) {
            throw new Failure(Reason.UNAVAILABLE);
        }
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            Future<byte[]> stdout = readers.submit(() -> readLimited(process.getInputStream(), maxStdoutBytes));
            Future<byte[]> stderr = readers.submit(() -> readLimited(process.getErrorStream(), MAX_STDERR_BYTES));
            Future<?> writer = readers.submit(() -> {
                try (var stdin = process.getOutputStream()) {
                    stdin.write(input);
                }
                return null;
            });
            writer.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                throw new Failure(Reason.TIMEOUT);
            }
            return new Result(process.exitValue(), stdout.get(remaining(deadline), TimeUnit.NANOSECONDS),
                    new String(stderr.get(remaining(deadline), TimeUnit.NANOSECONDS), StandardCharsets.UTF_8));
        } catch (TimeoutException exception) {
            process.destroyForcibly();
            throw new Failure(Reason.TIMEOUT);
        } catch (InterruptedException | ExecutionException exception) {
            process.destroyForcibly();
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Failure(Reason.FAILED);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static long remaining(long deadline) {
        return Math.max(1L, deadline - System.nanoTime());
    }

    private static byte[] readLimited(InputStream stream, int maxBytes) throws IOException {
        try (stream; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > maxBytes) throw new IOException("CLI output limit exceeded");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    record Result(int exitCode, byte[] stdout, String stderr) { }

    enum Reason { UNAVAILABLE, TIMEOUT, FAILED }

    static final class Failure extends RuntimeException {
        private final Reason reason;

        Failure(Reason reason) { this.reason = reason; }

        Reason reason() { return reason; }
    }
}
