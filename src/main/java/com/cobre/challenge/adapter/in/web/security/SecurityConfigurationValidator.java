package com.cobre.challenge.adapter.in.web.security;

import com.cobre.challenge.adapter.in.web.security.config.JwtProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * ADR-007 §7 / TASK-008-22: fails application startup on any of six configurations that would
 * weaken the JWT trust anchor. A warning in a healthy-looking boot log is not a control (A02,
 * A10) — every rule below throws {@link IllegalStateException} rather than logging.
 *
 * <p>Deliberately no fallback: no default key, no "use the static key if JWKS is unreachable"
 * path. If JWKS cannot be reached at runtime, requests fail 401 and the service stays up to
 * serve health probes and the internal pipeline — turning a transient IdP outage into a
 * permanent authentication bypass is exactly what this class exists to prevent.
 *
 * <p>Each rule is a separately named, static, pure method over plain inputs (profiles, a
 * property value, a key size, a host string) precisely so it is unit-testable without a Spring
 * context; {@link #afterPropertiesSet()} is only the glue that reads the real configuration and
 * calls them.
 *
 * <p>Registers {@link JwtProperties} via {@code @EnableConfigurationProperties} here because no
 * other class in the codebase does — see {@code docs/concerns.md} for why this belongs to
 * TASK-008-20's own registration story and is fixed here as a minimal, scoped adjustment rather
 * than reopening that task.
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfigurationValidator implements InitializingBean {

    private static final Set<String> DEV_PROFILES = Set.of("local", "test");
    private static final int MIN_RSA_KEY_BITS = 2048;
    private static final String RELEASE_BUILD_PROPERTY = "challenge.build.release";

    private final Environment environment;
    private final OAuth2ResourceServerProperties resourceServerProperties;
    private final JwtProperties jwtProperties;

    public SecurityConfigurationValidator(
            Environment environment,
            OAuth2ResourceServerProperties resourceServerProperties,
            JwtProperties jwtProperties) {
        this.environment = environment;
        this.resourceServerProperties = resourceServerProperties;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> activeProfiles = List.of(environment.getActiveProfiles());
        OAuth2ResourceServerProperties.Jwt jwt = resourceServerProperties.getJwt();
        Resource publicKeyLocation = jwt.getPublicKeyLocation();
        String issuerUri = jwt.getIssuerUri();
        boolean releaseBuild = environment.getProperty(RELEASE_BUILD_PROPERTY, Boolean.class, Boolean.FALSE);

        rejectStaticKeyOutsideDevProfiles(activeProfiles, publicKeyLocation != null);
        requireIssuerUriOutsideDevProfiles(activeProfiles, issuerUri);
        rejectUnsafeIssuerHost(activeProfiles, issuerUri);
        requireAudiencePresent(jwtProperties.audience());
        resolveRsaKeyBitLength(publicKeyLocation).ifPresent(SecurityConfigurationValidator::rejectWeakKey);
        rejectLocalProfileInReleaseBuild(activeProfiles, releaseBuild);
    }

    /** Rule 1: a static key is a production/staging failure — only local/test may set it. */
    static void rejectStaticKeyOutsideDevProfiles(List<String> activeProfiles, boolean publicKeyLocationConfigured) {
        if (publicKeyLocationConfigured && !isDevProfile(activeProfiles)) {
            throw violation(1, "spring.security.oauth2.resourceserver.jwt.public-key-location",
                    "a static RSA public key is configured outside the local/test profiles");
        }
    }

    /** Rule 2: every non-local/test profile must have a JWKS issuer configured. */
    static void requireIssuerUriOutsideDevProfiles(List<String> activeProfiles, String issuerUri) {
        if (!isDevProfile(activeProfiles) && !StringUtils.hasText(issuerUri)) {
            throw violation(2, "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                    "the JWKS issuer URI is absent or blank outside the local/test profiles");
        }
    }

    /** Rule 3: outside local/test, the issuer host must not be loopback, private or link-local. */
    static void rejectUnsafeIssuerHost(List<String> activeProfiles, String issuerUri) {
        if (isDevProfile(activeProfiles) || !StringUtils.hasText(issuerUri)) {
            return;
        }
        String host = extractHost(issuerUri);
        if (host != null && isUnsafeHost(host)) {
            throw violation(3, "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                    "the issuer host is loopback, private or link-local");
        }
    }

    /** Rule 4: an audience-less resource server accepts any token from the issuer, in any profile. */
    static void requireAudiencePresent(String audience) {
        if (!StringUtils.hasText(audience)) {
            throw violation(4, "challenge.security.jwt.audience", "no audience is configured");
        }
    }

    /** Rule 5: checks the resolved key's modulus size, not a configured claim about it. */
    static void rejectWeakKey(int modulusBitLength) {
        if (modulusBitLength < MIN_RSA_KEY_BITS) {
            throw violation(5, "spring.security.oauth2.resourceserver.jwt.public-key-location",
                    "the configured RSA key is smaller than " + MIN_RSA_KEY_BITS + " bits");
        }
    }

    /** Rule 6: a production image starting in the local profile is itself the misconfiguration. */
    static void rejectLocalProfileInReleaseBuild(List<String> activeProfiles, boolean releaseBuild) {
        if (releaseBuild && activeProfiles.contains("local")) {
            throw violation(6, RELEASE_BUILD_PROPERTY, "the local profile is active in a release build");
        }
    }

    private static boolean isDevProfile(List<String> activeProfiles) {
        return activeProfiles.stream().anyMatch(DEV_PROFILES::contains);
    }

    private static String extractHost(String issuerUri) {
        try {
            return URI.create(issuerUri).getHost();
        } catch (IllegalArgumentException malformedUri) {
            return null;
        }
    }

    /**
     * Purely string-based classification (no DNS resolution — a startup validator must not
     * depend on network reachability to decide whether to boot).
     */
    private static boolean isUnsafeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.equals("::1") || normalized.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (isIPv4Literal(normalized)) {
            int[] octets = parseOctets(normalized);
            int first = octets[0];
            int second = octets[1];
            return first == 127 // loopback
                    || first == 10 // RFC1918
                    || (first == 172 && second >= 16 && second <= 31) // RFC1918
                    || (first == 192 && second == 168) // RFC1918
                    || (first == 169 && second == 254); // link-local
        }
        return normalized.startsWith("fe80:") || normalized.startsWith("fc") || normalized.startsWith("fd");
    }

    private static boolean isIPv4Literal(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }

    private static int[] parseOctets(String ipv4) {
        String[] parts = ipv4.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(parts[i]);
        }
        return octets;
    }

    private Optional<Integer> resolveRsaKeyBitLength(Resource publicKeyLocation) {
        if (publicKeyLocation == null) {
            return Optional.empty();
        }
        try (InputStream in = publicKeyLocation.getInputStream()) {
            RSAPublicKey key = (RSAPublicKey) RsaKeyConverters.x509().convert(in);
            return Optional.of(key.getModulus().bitLength());
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "SecurityConfigurationValidator rule 5: unable to read the configured RSA public key", e);
        }
    }

    private static IllegalStateException violation(int rule, String property, String reason) {
        return new IllegalStateException(
                "SecurityConfigurationValidator rule " + rule + " violated (" + property + "): " + reason);
    }
}
