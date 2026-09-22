package com.cobre.challenge.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-007 §2 step 9 / TASK-008-23: a valid token lacking the required authority gets a fixed 403
 * problem-detail body that names no scope — the request was refused, not why in terms an
 * attacker could use to enumerate the authority model.
 *
 * <p>Unlike the 401 entry point, the {@link Authentication} here is a verified
 * {@link JwtAuthenticationToken} (this handler only runs after successful authentication), so
 * {@code client_id}/{@code sub} are read directly from the token for the log line, never from a
 * best-effort decode.
 */
public final class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAccessDeniedHandler.class);
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    private final byte[] body;

    public ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        this.body = buildBody(objectMapper);
    }

    private static byte[] buildBody(ObjectMapper objectMapper) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Forbidden");
        problem.setDetail("The request was refused.");
        try {
            return objectMapper.writeValueAsBytes(problem);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to build the fixed 403 problem-detail body", e);
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        logDenial(request);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(PROBLEM_JSON.toString());
        response.getOutputStream().write(body);
    }

    private void logDenial(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String clientId = null;
        String subject = null;
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Jwt jwt = jwtAuthentication.getToken();
            clientId = jwt.getClaimAsString("client_id");
            subject = jwt.getSubject();
        }
        // ADR-007 §9: any 403 on the replay scope is a signal worth alerting on, not noise.
        log.warn(
                "Authorization denied: client_id={} sub={} path={} outcome={}",
                clientId,
                subject,
                request.getRequestURI(),
                "denied");
    }
}
