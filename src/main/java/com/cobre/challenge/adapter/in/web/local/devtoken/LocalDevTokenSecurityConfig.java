package com.cobre.challenge.adapter.in.web.local.devtoken;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permits unauthenticated access to the local dev-token minting path, under the {@code local}
 * profile only, mirroring {@code LocalEventGeneratorSecurityConfig}. Requesting a token needs no
 * token itself - it plays the role of the IdP, which does not authenticate to itself.
 */
@Configuration
@EnableWebSecurity
@Profile("local")
class LocalDevTokenSecurityConfig {

    @Bean
    @Order(6)
    SecurityFilterChain localDevTokenFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/local/dev-token/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
