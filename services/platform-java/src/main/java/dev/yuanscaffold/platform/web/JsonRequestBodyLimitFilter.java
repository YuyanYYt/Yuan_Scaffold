package dev.yuanscaffold.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Bounds raw JSON before Spring MVC parses it. Registered inside the security chain after authorization. */
public final class JsonRequestBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String ERROR_CODE = "REQUEST_BODY_TOO_LARGE";
    private static final String ERROR_MESSAGE = "JSON request body exceeds 1 MiB";

    private final ObjectMapper mapper;

    public JsonRequestBodyLimitFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (!method.equals("POST") && !method.equals("PUT") && !method.equals("PATCH")
                && !method.equals("DELETE")) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType == null) {
            return true;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return !mediaType.getType().equalsIgnoreCase("application")
                    || !(mediaType.getSubtype().equalsIgnoreCase("json")
                    || mediaType.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json"));
        } catch (IllegalArgumentException invalidContentType) {
            return true;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(ERROR_CODE, ERROR_MESSAGE, Instant.now()));
    }

    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final ServletInputStream input;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
            this.input = new ByteArrayServletInputStream(body);
        }

        @Override
        public ServletInputStream getInputStream() {
            return input;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(input, charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        ByteArrayServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("Asynchronous request body reads are not supported");
        }
    }
}
