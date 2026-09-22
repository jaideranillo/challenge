package com.cobre.challenge.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-007 §2 step 9 / TASK-008-23: every distinct authentication failure yields a byte-identical
 * 401 body. An expired token, a bad signature, a wrong audience and a missing header are all free
 * reconnaissance if the response tells them apart, so this entry point ignores which
 * {@link AuthenticationException} it was handed and always writes the same fixed body,
 * precomputed once. No {@code WWW-Authenticate} header is set — the default bearer-token entry
 * point's {@code error_description} is exactly the leak this class exists to remove.
 *
 * <p>The response never varies; the log line may, per A09. {@code client_id}/{@code sub} are
 * read on a best-effort basis by decoding (never verifying) the bearer token's payload, purely
 * for operator diagnostics — the token value and signature are never logged.
 */
public final class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAuthenticationEntryPoint.class);
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");
    private static final String CLIENT_ID_CLAIM = "client_id";
    private static final String SUBJECT_CLAIM = "sub";

    private final ObjectMapper objectMapper;
    private final byte[] body;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.body = buildBody(objectMapper);
    }

    private static byte[] buildBody(ObjectMapper objectMapper) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Unauthorized");
        problem.setDetail("Authentication is required and has failed or has not been provided.");
        try {
            return objectMapper.writeValueAsBytes(problem);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to build the fixed 401 problem-detail body", e);
        }
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        logFailure(request, authException);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(PROBLEM_JSON.toString());
        response.getOutputStream().write(body);
    }

    private void logFailure(HttpServletRequest request, AuthenticationException authException) {
        Map<String, String> claims = bestEffortClaims(request);
        log.warn(
                "Authentication failed: client_id={} sub={} path={} outcome={} cause={}",
                claims.get(CLIENT_ID_CLAIM),
                claims.get(SUBJECT_CLAIM),
                request.getRequestURI(),
                "denied",
                authException.getClass().getSimpleName());
    }

    /**
     * Decodes (never verifies) the bearer token's payload segment to recover {@code client_id}
     * and {@code sub} for the log line above. The signature is never checked here — the token
     * has already been rejected by the time this runs — and nothing decoded here is ever written
     * to the response.
     */
    private Map<String, String> bestEffortClaims(HttpServletRequest request) {
        try {
            String header = request.getHeader("Authorization");
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return Map.of();
            }
            String token = header.substring(7).trim();
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return Map.of();
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            Map<?, ?> raw = objectMapper.readValue(new String(payload, StandardCharsets.UTF_8), Map.class);
            String clientId = asString(raw.get(CLIENT_ID_CLAIM));
            String subject = asString(raw.get(SUBJECT_CLAIM));
            return Map.of(CLIENT_ID_CLAIM, clientId == null ? "" : clientId, SUBJECT_CLAIM, subject == null ? "" : subject);
        } catch (RuntimeException malformedToken) {
            return Map.of();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
