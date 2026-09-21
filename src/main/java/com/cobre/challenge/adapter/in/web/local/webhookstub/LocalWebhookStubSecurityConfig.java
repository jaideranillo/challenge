package com.cobre.challenge.adapter.in.web.local.webhookstub;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permits unauthenticated access to the local webhook stub's own paths,
 * under the {@code local} profile only. Does not implement ADR-007's
 * security design; every other path/profile keeps default Spring Security
 * auto-configuration.
 *
 * <p>Defining any {@link SecurityFilterChain} bean makes Spring Security's
 * auto-configured default chain back off entirely, which would otherwise
 * leave every other path unauthenticated. {@code defaultFilterChain} below
 * replicates that default (authenticated + HTTP Basic) for anything outside
 * the stub's paths so no other path is newly exposed.
 */
@Configuration
@EnableWebSecurity
@Profile("local")
class LocalWebhookStubSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain localWebhookStubFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/local/webhook-stub/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
