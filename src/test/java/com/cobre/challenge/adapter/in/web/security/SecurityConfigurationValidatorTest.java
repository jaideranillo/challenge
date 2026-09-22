package com.cobre.challenge.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TASK-008-22: unit tests, no Spring context. {@code SecurityConfigurationValidator}'s six rules
 * are static, pure methods over plain inputs; reflection is used only because the rule methods
 * are package-private by design (never called except from {@code afterPropertiesSet}), not
 * because a context is needed.
 */
class SecurityConfigurationValidatorTest {

    // --- Rule 1: no static key outside local/test ---

    @Test
    void rule1RejectsStaticKeyInProductionProfile() {
        assertThatIllegalStateException()
                .isThrownBy(() -> invokeVoid("rejectStaticKeyOutsideDevProfiles", List.of("production"), true))
                .withMessageContaining("rule 1")
                .withMessageContaining("public-key-location");
    }

    @Test
    void rule1AllowsStaticKeyInLocalProfile() {
        assertThatNoException().isThrownBy(() -> invokeVoid("rejectStaticKeyOutsideDevProfiles", List.of("local"), true));
    }

    @Test
    void rule1AllowsNoStaticKeyInProductionProfile() {
        assertThatNoException()
                .isThrownBy(() -> invokeVoid("rejectStaticKeyOutsideDevProfiles", List.of("production"), false));
    }

    // --- Rule 2: issuer URI required outside local/test ---

    @Test
    void rule2RejectsBlankIssuerInProductionProfile() {
        assertThatIllegalStateException()
                .isThrownBy(() -> invokeVoid("requireIssuerUriOutsideDevProfiles", List.of("production"), ""))
                .withMessageContaining("rule 2");
    }

    @Test
    void rule2AllowsBlankIssuerInTestProfile() {
        assertThatNoException().isThrownBy(() -> invokeVoid("requireIssuerUriOutsideDevProfiles", List.of("test"), ""));
    }

    @Test
    void rule2AllowsPresentIssuerInProductionProfile() {
        assertThatNoException().isThrownBy(() -> invokeVoid(
                "requireIssuerUriOutsideDevProfiles", List.of("production"), "https://idp.example.com/realm"));
    }

    // --- Rule 3: loopback/private/link-local issuer host rejected outside local/test ---

    @Test
    void rule3RejectsLoopbackIssuerHostInProductionProfile() {
        assertThatIllegalStateException()
                .isThrownBy(() -> invokeVoid("rejectUnsafeIssuerHost", List.of("production"), "http://localhost:8080/realm"))
                .withMessageContaining("rule 3");
    }

    @Test
    void rule3RejectsPrivateRfc1918IssuerHostInProductionProfile() {
        assertThatIllegalStateException()
                .isThrownBy(() -> invokeVoid("rejectUnsafeIssuerHost", List.of("production"), "https://10.0.5.2/realm"));
    }

    @Test
    void rule3RejectsLinkLocalIssuerHostInProductionProfile() {
        assertThatIllegalStateException()
                .isThrownBy(() -> invokeVoid("rejectUnsafeIssuerHost", List.of("production"), "https://169.254.1.1/realm"));
    }

    @Test
    void rule3AllowsLoopbackIssuerHostInLocalProfile() {
        assertThatNoException()
                .isThrownBy(() -> invokeVoid("rejectUnsafeIssuerHost", List.of("local"), "http://localhost:8080/realm"));
    }

    @Test
    void rule3AllowsPublicIssuerHostInProductionProfile() {
        assertThatNoException().isThrownBy(
                () -> invokeVoid("rejectUnsafeIssuerHost", List.of("production"), "https://idp.example.com/realm"));
    }

    // --- Rule 4: audience required in every profile ---

    @Test
    void rule4RejectsBlankAudience() {
        assertThatIllegalStateException()
                .isThrownBy(() -> invokeVoid("requireAudiencePresent", "   "))
                .withMessageContaining("rule 4");
    }

    @Test
    void rule4RejectsNullAudience() {
        assertThatIllegalStateException().isThrownBy(() -> invokeVoid("requireAudiencePresent", new Object[] {null}));
    }

    @Test
    void rule4AllowsPresentAudience() {
        assertThatNoException().isThrownBy(() -> invokeVoid("requireAudiencePresent", "challenge-api"));
    }

    // --- Rule 5: RSA key must resolve to at least 2048 bits ---

    @Test
    void rule5RejectsKeySmallerThan2048Bits() {
        assertThatIllegalStateException()
                .isThrownBy(() -> invokeVoid("rejectWeakKey", 1024))
                .withMessageContaining("rule 5");
    }

    @Test
    void rule5AllowsKeyOfExactly2048Bits() {
        assertThatNoException().isThrownBy(() -> invokeVoid("rejectWeakKey", 2048));
    }

    @Test
    void rule5AllowsKeyLargerThan2048Bits() {
        assertThatNoException().isThrownBy(() -> invokeVoid("rejectWeakKey", 4096));
    }

    // --- Rule 6: local profile must not be active in a release build ---

    @Test
    void rule6RejectsLocalProfileInAReleaseBuild() {
        assertThatIllegalStateException()
                .isThrownBy(() -> invokeVoid("rejectLocalProfileInReleaseBuild", List.of("local"), true))
                .withMessageContaining("rule 6");
    }

    @Test
    void rule6AllowsLocalProfileInANonReleaseBuild() {
        assertThatNoException().isThrownBy(() -> invokeVoid("rejectLocalProfileInReleaseBuild", List.of("local"), false));
    }

    @Test
    void rule6AllowsNonLocalProfileInAReleaseBuild() {
        assertThatNoException().isThrownBy(() -> invokeVoid("rejectLocalProfileInReleaseBuild", List.of("production"), true));
    }

    // --- Failure messages never carry key/token/secret content ---

    @Test
    void failureMessagesNameTheRuleAndPropertyButNoSecretValue() {
        String secretLookingIssuer = "http://localhost:8080/realm?client_secret=super-secret-value";
        assertThatIllegalStateException()
                .isThrownBy(() -> invokeVoid("rejectUnsafeIssuerHost", List.of("production"), secretLookingIssuer))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("super-secret-value"));
    }

    // --- reflection helper: rule methods are intentionally package-private, non-static-callable via test ---

    private static void invokeVoid(String methodName, Object... args) throws Throwable {
        Class<?> validatorClass = Class.forName("com.cobre.challenge.adapter.in.web.security.SecurityConfigurationValidator");
        for (Method method : validatorClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                try {
                    method.invoke(null, args);
                    return;
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new NoSuchMethodException(methodName);
    }
}
