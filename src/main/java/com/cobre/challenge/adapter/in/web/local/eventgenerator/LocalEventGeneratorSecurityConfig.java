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
 * Permits unauthenticated access to the local event generator's own path, under the {@code local}
 * profile only, mirroring {@code LocalWebhookStubSecurityConfig}. Does not touch {@code
 * /internal/events}'s own security chain.
 */
@Configuration
@EnableWebSecurity
@Profile("local")
class LocalEventGeneratorSecurityConfig {

    @Bean
    @Order(5)
    SecurityFilterChain localEventGeneratorFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/local/event-generator/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
