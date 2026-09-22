package com.cobre.challenge.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

/**
 * TASK-008-23: unit tests, no Spring context. Both handlers are called directly with
 * {@link MockHttpServletRequest}/{@link MockHttpServletResponse}.
 */
class ProblemDetailHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- Authentication entry point: identical-body property ---

    @Test
    void everyAuthenticationFailureYieldsAByteIdenticalBody() throws Exception {
        ProblemDetailAuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(objectMapper);

        byte[] noToken = commence(entryPoint, null, new InsufficientAuthenticationException("no token"));
        byte[] malformed = commence(entryPoint, "not-a-jwt", new BadCredentialsException("malformed"));
        byte[] badSignature = commence(
                entryPoint, forgedToken(Map.of("client_id", "CLIENT001")),
                new InvalidBearerTokenException("bad signature"));
        byte[] expired = commence(
                entryPoint, forgedToken(Map.of("client_id", "CLIENT001")),
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token", "expired", null)));
        byte[] wrongAudience = commence(
                entryPoint, forgedToken(Map.of("client_id", "CLIENT001")),
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token", "wrong aud", null)));

        assertThat(noToken).isEqualTo(malformed).isEqualTo(badSignature).isEqualTo(expired).isEqualTo(wrongAudience);
    }

    @Test
    void authenticationFailureIs401WithProblemJsonContentType() throws Exception {
        ProblemDetailAuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    void bodyNamesNoExceptionClassNoStackTraceAndNoScope() throws Exception {
        ProblemDetailAuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("wrong audience: attacker-service"));

        String body = response.getContentAsString();
        assertThat(body).doesNotContain("BadCredentialsException");
        assertThat(body).doesNotContain("attacker-service");
        assertThat(body).doesNotContain("notifications:");
        assertThat(body).doesNotContain("\tat ");
    }

    @Test
    void noWwwAuthenticateHeaderIsSet() throws Exception {
        ProblemDetailAuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getHeader("WWW-Authenticate")).isNull();
    }

    // --- Access denied handler ---

    @Test
    void accessDeniedIs403WithProblemJsonAndNamesNoScope() throws Exception {
        ProblemDetailAccessDeniedHandler handler = new ProblemDetailAccessDeniedHandler(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        setAuthenticatedJwt("CLIENT001", "user-1", List.of("notifications:read"));

        handler.handle(request, response, new AccessDeniedException("missing notifications:replay"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).doesNotContain("notifications:replay");
        SecurityContextHolder.clearContext();
    }

    // --- helpers ---

    private byte[] commence(
            ProblemDetailAuthenticationEntryPoint entryPoint,
            String authorizationHeader,
            org.springframework.security.core.AuthenticationException authException)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorizationHeader != null) {
            request.addHeader("Authorization", "Bearer " + authorizationHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(request, response, authException);
        return response.getContentAsByteArray();
    }

    private static String forgedToken(Map<String, Object> claims) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mapper.writeValueAsBytes(Map.of("alg", "RS256")));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(claims));
        return header + "." + payload + ".invalid-signature";
    }

    private static void setAuthenticatedJwt(String clientId, String subject, List<String> scopes) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(60))
                .subject(subject)
                .claim("client_id", clientId)
                .claim("scope", String.join(" ", scopes))
                .build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of());
        SecurityContextImpl context = new SecurityContextImpl(authentication);
        SecurityContextHolder.setContext(context);
    }
}
