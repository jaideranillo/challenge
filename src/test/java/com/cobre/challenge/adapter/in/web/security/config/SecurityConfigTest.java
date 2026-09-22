package com.cobre.challenge.adapter.in.web.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TASK-008-21: plain-JUnit structural checks, no Spring context (a real {@code HttpSecurity}
 * cannot be built headlessly). Verifies the four chain beans exist with the expected
 * {@code @Order} values and matchers by reflecting over {@link SecurityConfig}'s declared
 * methods and constants, per this phase's rule.
 *
 * <p>Request-level behavior (unlisted-path 403, actuator split, probe access, missing-scope 403)
 * is DEFERRED to the Testcontainers phase (TASK-008-28).
 */
class SecurityConfigTest {

    @Test
    void classEnablesWebAndMethodSecurity() {
        assertThat(SecurityConfig.class.isAnnotationPresent(EnableWebSecurity.class)).isTrue();
        assertThat(SecurityConfig.class.isAnnotationPresent(EnableMethodSecurity.class)).isTrue();
    }

    @Test
    void fourFilterChainBeansExistWithDisjointOrders() {
        List<Method> chainMethods = filterChainMethods(SecurityConfig.class);
        assertThat(chainMethods).hasSize(4);

        List<Integer> orders = chainMethods.stream().map(SecurityConfigTest::orderOf).sorted().toList();
        assertThat(orders).containsExactly(1, 2, 3, Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void actuatorChainIsOrderOneAndMatchesActuatorPaths() throws Exception {
        Method actuatorChain = SecurityConfig.class.getDeclaredMethod("actuatorFilterChain", getHttpSecurityClass());
        assertThat(orderOf(actuatorChain)).isEqualTo(1);
        assertThat(constant("ACTUATOR_MATCHER")).isEqualTo("/actuator/**");
    }

    @Test
    void ingestChainIsOrderTwoAndMatchesInternalPaths() throws Exception {
        Method ingestChain = SecurityConfig.class.getDeclaredMethod("ingestFilterChain", getHttpSecurityClass());
        assertThat(orderOf(ingestChain)).isEqualTo(2);
        assertThat(constant("INTERNAL_MATCHER")).isEqualTo("/internal/**");
    }

    @Test
    void clientApiChainIsOrderThreeAndMatchesNotificationEventsPaths() throws Exception {
        Method clientApiChain = Arrays.stream(SecurityConfig.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("clientApiFilterChain"))
                .findFirst()
                .orElseThrow();
        assertThat(orderOf(clientApiChain)).isEqualTo(3);
        assertThat(constant("CLIENT_API_MATCHER")).isEqualTo("/notification_events/**");
    }

    @Test
    void terminalChainIsLowestPrecedence() throws Exception {
        Method terminalChain = SecurityConfig.class.getDeclaredMethod("terminalDenyFilterChain", getHttpSecurityClass());
        assertThat(orderOf(terminalChain)).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void localStubChainSitsBeforeTheTerminalChainAndTheOldCatchAllIsGone() throws Exception {
        Class<?> stubConfigClass =
                Class.forName("com.cobre.challenge.adapter.in.web.local.webhookstub.LocalWebhookStubSecurityConfig");
        Method stubChain = stubConfigClass.getDeclaredMethod("localWebhookStubFilterChain", getHttpSecurityClass());
        assertThat(orderOf(stubChain)).isEqualTo(4).isLessThan(Ordered.LOWEST_PRECEDENCE);

        boolean defaultFilterChainStillExists = Arrays.stream(stubConfigClass.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("defaultFilterChain"));
        assertThat(defaultFilterChainStillExists).isFalse();
    }

    // --- reflection helpers ---

    private static List<Method> filterChainMethods(Class<?> configClass) {
        return Arrays.stream(configClass.getDeclaredMethods())
                .filter(m -> m.getReturnType().equals(SecurityFilterChain.class))
                .toList();
    }

    private static int orderOf(Method method) {
        Order order = method.getAnnotation(Order.class);
        assertThat(order).as("method %s must declare @Order", method.getName()).isNotNull();
        return order.value();
    }

    private static Class<?> getHttpSecurityClass() throws ClassNotFoundException {
        return Class.forName("org.springframework.security.config.annotation.web.builders.HttpSecurity");
    }

    private static String constant(String fieldName) throws Exception {
        Field field = SecurityConfig.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
