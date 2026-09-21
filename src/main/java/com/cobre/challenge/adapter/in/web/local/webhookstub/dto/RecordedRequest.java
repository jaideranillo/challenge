package com.cobre.challenge.adapter.in.web.local.webhookstub.dto;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire payload of {@code GET /local/webhook-stub/requests}: a single request
 * captured by the local webhook stub.
 */
public record RecordedRequest(
        String method,
        Map<String, List<String>> headers,
        String body,
        Instant receivedAt) {

    private static final int MAX_BODY_BYTES = 65536;

    public RecordedRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? "" : body;
    }

    public static RecordedRequest from(HttpServletRequest request) throws IOException {
        return new RecordedRequest(request.getMethod(), headersOf(request), bodyOf(request), Instant.now());
    }

    private static Map<String, List<String>> headersOf(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, Collections.list(request.getHeaders(name)));
            }
        }
        return headers;
    }

    private static String bodyOf(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
