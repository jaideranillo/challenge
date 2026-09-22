package com.cobre.challenge.adapter.in.web.local.webhookstub;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permits unauthenticated access to the local webhook stub's own paths, under the {@code local}
 * profile only. Does not implement ADR-007's security design otherwise.
 *
 * <p>TASK-008-21: {@code SecurityConfig}'s terminal chain (order
 * {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE}) is now the deny-by-default rule for
 * every path this stub doesn't claim, so the catch-all {@code defaultFilterChain} this class used
 * to define (authenticated + HTTP Basic, replicating Spring Security's auto-configured default)
 * has been removed — leaving both would mean bean ordering decided the whole application's
 * security posture by accident. This chain is ordered before that terminal chain so the stub
 * path still resolves here first.
 */
@Configuration
@EnableWebSecurity
@Profile("local")
class LocalWebhookStubSecurityConfig {

    @Bean
    @Order(4)
    SecurityFilterChain localWebhookStubFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/local/webhook-stub/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
