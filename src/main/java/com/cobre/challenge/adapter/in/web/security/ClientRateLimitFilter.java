package com.cobre.challenge.adapter.in.web.security;

import com.cobre.challenge.adapter.in.web.security.config.RateLimitProperties;
import com.cobre.challenge.domain.model.tenant.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-007 §6 / TASK-008-24: per-{@code client_id} read and replay budgets. Runs strictly
 * <strong>after</strong> {@code BearerTokenAuthenticationFilter} in chain 3 (wired by
 * {@code SecurityConfig}) — the key is the verified {@code client_id} claim, resolved the same
 * way every other tenant-scoped read does ({@link AuthenticatedTenantResolver}), never an IP or
 * an unauthenticated value.
 *
 * <p>Replay enforces both its per-minute and its per-day bucket; the minute bucket may be
 * consumed even when the day bucket independently rejects the same request (short-circuit
 * evaluation, minute checked first). That only tightens the effective budget on the rare day
 * where both are near-exhausted together — never loosens it — so it is left as the simplest
 * correct behavior rather than added compensating logic.
 *
 * <p><strong>Per-pod budget, not global</strong> (ADR-007 §6): with N instances a client's
 * effective budget is N times the configured number. The same bounded over-count ADR-006 §1.2
 * already accepts for the circuit breaker; the alternative is Redis or a database round trip on
 * every request, which this filter deliberately does not add (A03).
 *
 * <p><strong>Bounded, evicting key space:</strong> the client-keyed bucket map is a
 * synchronized, access-ordered {@link LinkedHashMap} capped at
 * {@link RateLimitProperties#maxTrackedClients()}, evicting the least-recently-used client once
 * full — an unbounded map keyed by a claim this service does not control the cardinality of
 * would itself be a denial-of-service vector.
 *
 * <p><strong>Virtual-thread safety:</strong> the only {@code synchronized} section here is
 * {@link Collections#synchronizedMap}'s internal lock around a pure in-memory map lookup/insert
 * (no I/O, no other lock acquired, no blocking call inside it). Under Java 21's virtual-thread
 * model a {@code synchronized} block pins the carrier only while it executes; since this one
 * never blocks, the pin lasts a few in-memory operations and is released immediately — the
 * pinning risk the ADR warns about is a lock held <em>across a blocking call</em>, which does not
 * happen here. {@link TokenBucket} itself is fully lock-free (compare-and-set).
 */
public class ClientRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientRateLimitFilter.class);
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    private final AuthenticatedTenantResolver tenantResolver;
    private final RateLimitProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Map<String, ClientBuckets> buckets;

    public ClientRateLimitFilter(
            AuthenticatedTenantResolver tenantResolver,
            RateLimitProperties properties,
            Clock clock,
            ObjectMapper objectMapper) {
        this.tenantResolver = tenantResolver;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.buckets = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ClientBuckets> eldest) {
                return size() > properties.maxTrackedClients();
            }
        });
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        TenantId tenant = tenantResolver.resolve(SecurityContextHolder.getContext().getAuthentication());
        ClientBuckets clientBuckets = bucketsFor(tenant.value());

        if (isReplayRequest(request)) {
            if (!clientBuckets.replayMinute().tryConsume()) {
                reject(response, tenant.value(), "REPLAY_MINUTE", clientBuckets.replayMinute());
                return;
            }
            if (!clientBuckets.replayDay().tryConsume()) {
                reject(response, tenant.value(), "REPLAY_DAY", clientBuckets.replayDay());
                return;
            }
        } else if (!clientBuckets.read().tryConsume()) {
            reject(response, tenant.value(), "READ", clientBuckets.read());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static boolean isReplayRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().endsWith("/replay");
    }

    private ClientBuckets bucketsFor(String clientId) {
        return buckets.computeIfAbsent(clientId, id -> new ClientBuckets(
                new TokenBucket(properties.readBurst(), properties.readRequestsPerMinute(), Duration.ofMinutes(1), clock),
                new TokenBucket(
                        properties.replayBurst(), properties.replayRequestsPerMinute(), Duration.ofMinutes(1), clock),
                new TokenBucket(
                        properties.replayRequestsPerDay(), properties.replayRequestsPerDay(), Duration.ofDays(1), clock)));
    }

    private void reject(HttpServletResponse response, String clientId, String bucket, TokenBucket exhaustedBucket)
            throws IOException {
        // A09 / ADR-007 §9: rate-limit exhaustion by client is worth alerting on. Never log the token.
        log.warn("Rate limit exceeded: client_id={} bucket={}", clientId, bucket);

        long retryAfterSeconds = Math.max(1, exhaustedBucket.timeUntilNextToken().toSeconds() + 1);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(PROBLEM_JSON.toString());
        response.getOutputStream().write(problemBody());
    }

    private byte[] problemBody() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setTitle("Too Many Requests");
        problem.setDetail("The rate limit for this client has been exceeded.");
        try {
            return objectMapper.writeValueAsBytes(problem);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to build the 429 problem-detail body", e);
        }
    }

    /** Test support only: the number of clients currently tracked in the bounded bucket map. */
    int trackedClientCount() {
        return buckets.size();
    }

    private record ClientBuckets(TokenBucket read, TokenBucket replayMinute, TokenBucket replayDay) {
    }
}
