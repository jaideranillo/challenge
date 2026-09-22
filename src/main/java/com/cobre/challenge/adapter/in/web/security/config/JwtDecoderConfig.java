package com.cobre.challenge.adapter.in.web.security.config;

import com.cobre.challenge.adapter.in.web.security.JwtClaimValidators;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Builds the {@code JwtDecoder} and authority converter per ADR-007 §3-4.
 *
 * <p>Defines its own {@code JwtDecoder} bean rather than relying on Spring Boot's
 * auto-configured one: this is an explicit RS256 allow-list (the algorithm-confusion
 * mitigation), a 30-second clock skew (not the 60-second default), and the full claim
 * contract of §3, none of which is what auto-configuration produces on its own. Defining the
 * bean here makes {@code @ConditionalOnMissingBean(JwtDecoder.class)} in Spring Boot's own
 * configuration back off, so there is exactly one source of decoder behavior.
 *
 * <p>Key resolution branches on {@code spring.security.oauth2.resourceserver.jwt.*}, already
 * bound per environment (TASK-008-02/-03): {@code public-key-location} (local/test) or
 * {@code issuer-uri} (every other profile, JWKS). Never both — {@code SecurityConfigurationValidator}
 * (TASK-008-22) is what refuses to boot on that misconfiguration; this class only picks whichever
 * is present.
 */
@Configuration
public class JwtDecoderConfig {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Bean
    public NimbusJwtDecoder jwtDecoder(OAuth2ResourceServerProperties resourceServerProperties, JwtProperties jwtProperties)
            throws Exception {
        OAuth2ResourceServerProperties.Jwt jwt = resourceServerProperties.getJwt();
        NimbusJwtDecoder decoder = buildDecoder(jwt);
        decoder.setJwtValidator(buildValidator(jwt.getIssuerUri(), jwtProperties));
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        // ADR-007 §4: empty prefix, so "notifications:replay" in the token is exactly the
        // authority a rule references — no SCOPE_ prefix, no mismatch class of bug.
        authoritiesConverter.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    private NimbusJwtDecoder buildDecoder(OAuth2ResourceServerProperties.Jwt jwt) throws Exception {
        Resource publicKeyLocation = jwt.getPublicKeyLocation();
        if (publicKeyLocation != null) {
            return NimbusJwtDecoder.withPublicKey(readRsaPublicKey(publicKeyLocation))
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        }
        String issuerUri = jwt.getIssuerUri();
        Assert.state(
                StringUtils.hasText(issuerUri),
                "Neither spring.security.oauth2.resourceserver.jwt.public-key-location nor "
                        + ".issuer-uri is set");
        return NimbusJwtDecoder.withIssuerLocation(issuerUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }

    private RSAPublicKey readRsaPublicKey(Resource resource) throws Exception {
        try (InputStream in = resource.getInputStream()) {
            return (RSAPublicKey) RsaKeyConverters.x509().convert(in);
        }
    }

    private OAuth2TokenValidator<Jwt> buildValidator(String issuerUri, JwtProperties jwtProperties) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(CLOCK_SKEW));
        if (StringUtils.hasText(issuerUri)) {
            validators.add(new JwtIssuerValidator(issuerUri));
        }
        validators.add(JwtClaimValidators.audience(jwtProperties.audience()));
        validators.add(JwtClaimValidators.issuedAtNotInFuture(CLOCK_SKEW));
        validators.add(JwtClaimValidators.maxLifetime(jwtProperties.maxLifetime()));
        validators.add(JwtClaimValidators.subject());
        validators.add(JwtClaimValidators.clientId());
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
}
