package com.cobre.challenge.adapter.in.web.security.config;

import static org.springframework.security.config.Customizer.withDefaults;

import com.cobre.challenge.adapter.in.web.security.AuthenticatedTenantResolver;
import com.cobre.challenge.adapter.in.web.security.ClientRateLimitFilter;
import com.cobre.challenge.adapter.in.web.security.ProblemDetailAccessDeniedHandler;
import com.cobre.challenge.adapter.in.web.security.ProblemDetailAuthenticationEntryPoint;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-007 §2 / TASK-008-21: four ordered {@code SecurityFilterChain} beans, one per surface,
 * because the three surfaces authenticate differently (JWT, IAM SigV4, nothing) and merging them
 * into one chain with many {@code requestMatchers} is where permit-all mistakes are made. The
 * fourth chain is a terminal {@code denyAll()} on {@code /**} — an unlisted path is dead on
 * arrival, not "whatever the last chain happened to say".
 *
 * <p>Order 4 is reserved for {@code local}-profile-only chains defined elsewhere
 * ({@code LocalWebhookStubSecurityConfig}); this class's terminal chain is pinned to
 * {@link Ordered#LOWEST_PRECEDENCE} so it is always evaluated last regardless of what else is
 * registered.
 *
 * <p>{@code @EnableMethodSecurity} so TASK-008-25/-26 can annotate handlers with
 * {@code @PreAuthorize} in addition to (not instead of) chain 3's URL rules — two cheap checks
 * that fail independently.
 *
 * <p>Nothing here changes {@code SecurityContextHolder}'s strategy: it stays
 * {@code MODE_THREADLOCAL}. No security context is propagated to the relay, worker or DLQ beans.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(RateLimitProperties.class)
public class SecurityConfig {

    static final String ACTUATOR_MATCHER = "/actuator/**";
    static final String INTERNAL_MATCHER = "/internal/**";
    static final String CLIENT_API_MATCHER = "/notification_events/**";

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public SecurityConfig(JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Bean
    public ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new ProblemDetailAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public ProblemDetailAccessDeniedHandler problemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        return new ProblemDetailAccessDeniedHandler(objectMapper);
    }

    @Bean
    public ClientRateLimitFilter clientRateLimitFilter(
            AuthenticatedTenantResolver authenticatedTenantResolver,
            RateLimitProperties rateLimitProperties,
            ObjectMapper objectMapper) {
        return new ClientRateLimitFilter(authenticatedTenantResolver, rateLimitProperties, Clock.systemUTC(), objectMapper);
    }

    // Without this, Spring Boot's ServletContextInitializerBeans auto-registers any Filter bean
    // as a global servlet filter (running on every path, actuator included) in addition to the
    // explicit addFilterAfter(...) placement in chain 3 below - disabling that redundant global
    // registration is required, not optional, for a filter meant to run only inside one chain.
    @Bean
    public FilterRegistrationBean<ClientRateLimitFilter> clientRateLimitFilterRegistration(
            ClientRateLimitFilter clientRateLimitFilter) {
        FilterRegistrationBean<ClientRateLimitFilter> registration =
                new FilterRegistrationBean<>(clientRateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Chain 1 (ADR-007 §2): only the three health probe paths are unauthenticated (orchestrator
     * probes carry no token); every other actuator endpoint requires authentication and the
     * {@code ops} authority. {@code management.endpoint.health.show-details=never} belongs in
     * configuration, not here (A02) — it is set in {@code application.yaml}.
     */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(ACTUATOR_MATCHER)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")
                        .permitAll()
                        .anyRequest()
                        .hasAuthority("ops"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                        jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    /**
     * Chain 2 (ADR-007 §2): reserved for {@code /internal/**} and AWS IAM SigV4 verification per
     * ADR-002's Q10 — <strong>not implemented here</strong> (out of scope, follow-up
     * security-engineer task). No {@code oauth2ResourceServer()} is configured on this chain, so
     * a client bearer JWT is never accepted on this path.
     *
     * <p><strong>Handover — known state:</strong> with {@code anyRequest().authenticated()} and
     * no authentication mechanism wired, every request to {@code /internal/**} now fails
     * authentication and is denied (Spring Security's default anonymous authentication does not
     * satisfy {@code authenticated()}, so this falls through to a 401/403). This does not newly
     * break {@code /internal/events}: before this task no {@code SecurityFilterChain} covered
     * this path at all, so Spring Boot's auto-configured default chain already required
     * authentication for it in every profile. {@code EventIngestAcceptanceTest} and similar
     * tests already carry their own {@code @TestConfiguration} permit-all chain for this path and
     * are unaffected. The follow-up task must add SigV4 verification here before any producer can
     * reach this endpoint outside a test.
     */
    @Bean
    @Order(2)
    SecurityFilterChain ingestFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(INTERNAL_MATCHER)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /** Chain 3 (ADR-007 §2): the self-service client API, steps 1-9 in the ADR's order. */
    @Bean
    @Order(3)
    SecurityFilterChain clientApiFilterChain(
            HttpSecurity http,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler,
            ClientRateLimitFilter clientRateLimitFilter)
            throws Exception {
        http.securityMatcher(CLIENT_API_MATCHER) // step 1
                .csrf(AbstractHttpConfigurer::disable) // step 2: stateless bearer-token API, no cookie
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // step 3
                .cors(AbstractHttpConfigurer::disable) // step 4: server-to-server, no origin to allow-list yet
                .headers(headers -> headers // step 5: HSTS, nosniff, no-store, CSP default-src 'none'
                        .httpStrictTransportSecurity(withDefaults())
                        .contentTypeOptions(withDefaults())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'"))
                        .cacheControl(cacheControl -> cacheControl.disable())
                        .addHeaderWriter(new StaticHeadersWriter(HttpHeaders.CACHE_CONTROL, "no-store")))
                .oauth2ResourceServer(oauth2 -> oauth2 // step 6
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter))
                        // BearerTokenAuthenticationFilter's own AuthenticationException path
                        // (malformed/expired/wrong-audience token) is handled by this DSL's own
                        // entry point, separate from step 9's exceptionHandling(). Without this
                        // line, an invalid token gets Spring's raw default 401 body while every
                        // other 401 gets our RFC 9457 shape - exactly the distinguishable-failure
                        // oracle ADR-007 §5.5 requires closing for every failure alike.
                        .authenticationEntryPoint(authenticationEntryPoint))
                // step 7: placed right before AuthorizationFilter (i.e. after
                // ExceptionTranslationFilter, not merely after BearerTokenAuthenticationFilter) -
                // AnonymousAuthenticationFilter has not run yet at BearerTokenAuthenticationFilter's
                // position, so an unauthenticated request has no Authentication at all there and
                // AuthenticatedTenantResolver's exception would escape uncaught, ahead of
                // ExceptionTranslationFilter, and never reach step 9's entry point.
                .addFilterBefore(clientRateLimitFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(authorize -> authorize // step 8
                        .requestMatchers(HttpMethod.GET, "/notification_events", "/notification_events/*")
                        .hasAuthority("notifications:read")
                        .requestMatchers(HttpMethod.POST, "/notification_events/*/replay")
                        .hasAuthority("notifications:replay")
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(exceptionHandling -> exceptionHandling // step 9
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * Chain 4, terminal: any path not claimed by chains 1-3 (or the {@code local}-profile stub at
     * order 4) is denied by default. A new controller on a new path is dead on arrival until it
     * is deliberately added to a chain above.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain terminalDenyFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
