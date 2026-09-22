package com.cobre.challenge.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.adapter.in.web.security.config.RateLimitProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

/**
 * TASK-008-24: unit tests, no Spring context. Time is injected via a manually-advanced
 * {@link MutableClock}; no test sleeps.
 */
class ClientRateLimitFilterTest {

    private final AuthenticatedTenantResolver tenantResolver = new AuthenticatedTenantResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readUnderBudgetPasses() throws Exception {
        ClientRateLimitFilter filter = filterWith(properties(2, 60, 1, 1, 1, 100));
        authenticateAs("CLIENT001");
        AtomicInteger chainCalls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(getRequest("/notification_events"), response, (req, res) -> chainCalls.incrementAndGet());

        assertThat(chainCalls.get()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void readBudgetExhaustsAtTheBurstLimit() throws Exception {
        ClientRateLimitFilter filter = filterWith(properties(2, 60, 1, 1, 1, 100));
        authenticateAs("CLIENT001");
        AtomicInteger chainCalls = new AtomicInteger();

        consume(filter, "/notification_events", chainCalls);
        consume(filter, "/notification_events", chainCalls);
        MockHttpServletResponse third = consume(filter, "/notification_events", chainCalls);

        assertThat(chainCalls.get()).isEqualTo(2);
        assertThat(third.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(third.getHeader("Retry-After")).isNotNull();
        assertThat(third.getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    void burstAllowanceBehavesAsConfigured() throws Exception {
        ClientRateLimitFilter filter = filterWith(properties(5, 300, 1, 1, 1, 100));
        authenticateAs("CLIENT001");
        AtomicInteger chainCalls = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            consume(filter, "/notification_events", chainCalls);
        }
        MockHttpServletResponse sixth = consume(filter, "/notification_events", chainCalls);

        assertThat(chainCalls.get()).isEqualTo(5);
        assertThat(sixth.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void refillsAfterTheWindow() throws Exception {
        ClientRateLimitFilter filter = filterWith(properties(1, 1, 1, 1, 1, 100));
        authenticateAs("CLIENT001");
        AtomicInteger chainCalls = new AtomicInteger();

        consume(filter, "/notification_events", chainCalls);
        MockHttpServletResponse exhausted = consume(filter, "/notification_events", chainCalls);
        assertThat(exhausted.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        clock.advance(Duration.ofMinutes(1));
        MockHttpServletResponse afterRefill = consume(filter, "/notification_events", chainCalls);

        assertThat(afterRefill.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chainCalls.get()).isEqualTo(2);
    }

    @Test
    void replayEnforcesThePerMinuteLimit() throws Exception {
        ClientRateLimitFilter filter = filterWith(properties(100, 100, 1, 1000, 1, 100));
        authenticateAs("CLIENT001");
        AtomicInteger chainCalls = new AtomicInteger();

        consume(filter, "/notification_events/evt-1/replay", "POST", chainCalls);
        MockHttpServletResponse second = consume(filter, "/notification_events/evt-1/replay", "POST", chainCalls);

        assertThat(chainCalls.get()).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void replayEnforcesThePerDayLimitIndependentlyOfThePerMinuteLimit() throws Exception {
        // Minute bucket generously sized so it never blocks; day bucket capped at 1.
        ClientRateLimitFilter filter = filterWith(properties(100, 100, 1000, 1, 1000, 100));
        authenticateAs("CLIENT001");
        AtomicInteger chainCalls = new AtomicInteger();

        consume(filter, "/notification_events/evt-1/replay", "POST", chainCalls);
        MockHttpServletResponse second = consume(filter, "/notification_events/evt-1/replay", "POST", chainCalls);

        assertThat(chainCalls.get()).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void readAndReplayBucketsAreIndependentForOneClient() throws Exception {
        ClientRateLimitFilter filter = filterWith(properties(1, 60, 100, 100, 100, 100));
        authenticateAs("CLIENT001");
        AtomicInteger chainCalls = new AtomicInteger();

        consume(filter, "/notification_events", chainCalls);
        MockHttpServletResponse readExhausted = consume(filter, "/notification_events", chainCalls);
        MockHttpServletResponse replayStillOk = consume(filter, "/notification_events/evt-1/replay", "POST", chainCalls);

        assertThat(readExhausted.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(replayStillOk.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void twoClientsAreIndependent() throws Exception {
        ClientRateLimitFilter filter = filterWith(properties(1, 60, 1, 1, 1, 100));
        AtomicInteger chainCalls = new AtomicInteger();

        authenticateAs("CLIENT001");
        MockHttpServletResponse clientOneFirst = consume(filter, "/notification_events", chainCalls);
        MockHttpServletResponse clientOneSecond = consume(filter, "/notification_events", chainCalls);

        authenticateAs("CLIENT002");
        MockHttpServletResponse clientTwoFirst = consume(filter, "/notification_events", chainCalls);

        assertThat(clientOneFirst.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(clientOneSecond.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(clientTwoFirst.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void theClientMapIsBoundedByEviction() throws Exception {
        ClientRateLimitFilter filter = filterWith(properties(1, 60, 1, 1, 1, 2));
        AtomicInteger chainCalls = new AtomicInteger();

        authenticateAs("CLIENT001");
        consume(filter, "/notification_events", chainCalls);
        authenticateAs("CLIENT002");
        consume(filter, "/notification_events", chainCalls);
        authenticateAs("CLIENT003");
        consume(filter, "/notification_events", chainCalls);

        assertThat(filter.trackedClientCount()).isLessThanOrEqualTo(2);
    }

    // --- helpers ---

    private ClientRateLimitFilter filterWith(RateLimitProperties properties) {
        return new ClientRateLimitFilter(tenantResolver, properties, clock, objectMapper);
    }

    private static RateLimitProperties properties(
            int readBurst, int readPerMinute, int replayPerMinute, int replayPerDay, int replayBurst, int maxTrackedClients) {
        return new RateLimitProperties(readPerMinute, readBurst, replayPerMinute, replayPerDay, replayBurst, maxTrackedClients);
    }

    private MockHttpServletResponse consume(ClientRateLimitFilter filter, String uri, AtomicInteger chainCalls)
            throws Exception {
        return consume(filter, uri, "GET", chainCalls);
    }

    private MockHttpServletResponse consume(ClientRateLimitFilter filter, String uri, String method, AtomicInteger chainCalls)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(getRequest(uri, method), response, (req, res) -> chainCalls.incrementAndGet());
        return response;
    }

    private static MockHttpServletRequest getRequest(String uri) {
        return getRequest(uri, "GET");
    }

    private static MockHttpServletRequest getRequest(String uri, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private static void authenticateAs(String clientId) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(60))
                .subject("user-1")
                .claim("client_id", clientId)
                .build();
        SecurityContextHolder.setContext(new SecurityContextImpl(new JwtAuthenticationToken(jwt, List.of())));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
