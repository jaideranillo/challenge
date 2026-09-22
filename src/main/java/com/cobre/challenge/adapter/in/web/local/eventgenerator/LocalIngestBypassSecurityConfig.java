package com.cobre.challenge.adapter.in.web.local.eventgenerator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * DEV-ONLY BYPASS, local profile only. Permits unauthenticated {@code POST /internal/events} so
 * {@link EventGeneratorController} can drive a demo through the real gateway-ingest HTTP step
 * instead of calling the use case in process.
 *
 * <p>This is <strong>not</strong> what real producer auth looks like. Production ingest is meant
 * to require AWS SigV4 (ADR-002 Q10) — not yet implemented (see {@code SecurityConfig
 * .ingestFilterChain}'s javadoc and {@code docs/concerns.md}) — so today {@code /internal/events}
 * is simply unreachable ({@code anyRequest().authenticated()}, no auth mechanism wired) in every
 * profile except this one. Do not read this class as a stand-in for SigV4; it exists purely so a
 * local demo has data to show.
 *
 * <p>Ordered ahead of {@code SecurityConfig.ingestFilterChain} (order 2) so this narrower matcher
 * wins for {@code /internal/events/**} specifically; the rest of {@code /internal/**} is untouched
 * and still denies everything, same as production.
 */
@Configuration
@EnableWebSecurity
@Profile("local")
class LocalIngestBypassSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain localIngestBypassFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/internal/events/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
