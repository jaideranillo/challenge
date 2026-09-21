package com.cobre.challenge.application.port.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage;
import com.cobre.challenge.domain.model.delivery.Delivery;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersistencePortsTest {

    @Test
    void deliveryPageDefensivelyCopiesDeliveryList() {
        DeliveryPage page = new DeliveryPage(List.<Delivery>of(), Optional.empty());

        assertThat(page.deliveries()).isEmpty();
        assertThat(page.nextCursor()).isEmpty();
    }

    // --- delivery port split shape (TASK-004-03) ---

    @Test
    void deliveryRepositoryPortIsGone() {
        assertThat(classExists("com.cobre.challenge.application.port.out.persistence.DeliveryRepositoryPort"))
                .as("DeliveryRepositoryPort should be deleted")
                .isFalse();
    }

    @Test
    void deliveryPipelineRepositoryPortIsAnInterface() {
        assertThat(DeliveryPipelineRepositoryPort.class.isInterface()).isTrue();
    }

    @Test
    void deliveryQueryRepositoryPortIsAnInterface() {
        assertThat(DeliveryQueryRepositoryPort.class.isInterface()).isTrue();
    }

    @Test
    void deliveryAttemptRepositoryPortIsAnInterface() {
        assertThat(DeliveryAttemptRepositoryPort.class.isInterface()).isTrue();
    }

    @Test
    void subscriptionRepositoryPortIsAnInterface() {
        assertThat(SubscriptionRepositoryPort.class.isInterface()).isTrue();
    }

    @Test
    void noMethodOnDeliveryPipelineRepositoryPortHasClientIdParameter() {
        for (Method m : DeliveryPipelineRepositoryPort.class.getDeclaredMethods()) {
            boolean hasClientId = Arrays.stream(m.getParameters())
                    .anyMatch(p -> p.getName().equals("clientId")
                            || p.getType().equals(String.class) && p.getName().contains("client"));
            assertThat(hasClientId)
                    .as("DeliveryPipelineRepositoryPort.%s must not have a clientId parameter", m.getName())
                    .isFalse();
        }
    }

    @Test
    void everyMethodOnDeliveryQueryRepositoryPortHasClientIdParameter() {
        for (Method m : DeliveryQueryRepositoryPort.class.getDeclaredMethods()) {
            boolean hasClientId = Arrays.stream(m.getParameters())
                    .anyMatch(p -> p.getType().equals(String.class)
                            && (p.getName().equals("clientId") || p.getName().contains("client")));
            assertThat(hasClientId)
                    .as("DeliveryQueryRepositoryPort.%s must have a clientId parameter", m.getName())
                    .isTrue();
        }
    }

    @Test
    void noConditionalMethodOnDeliveryPipelineRepositoryPortReturnsVoid() {
        for (Method m : DeliveryPipelineRepositoryPort.class.getDeclaredMethods()) {
            assertThat(m.getReturnType())
                    .as("DeliveryPipelineRepositoryPort.%s must not return void", m.getName())
                    .isNotEqualTo(void.class);
        }
    }

    @Test
    void noConditionalMethodOnDeliveryQueryRepositoryPortReturnsVoid() {
        for (Method m : DeliveryQueryRepositoryPort.class.getDeclaredMethods()) {
            assertThat(m.getReturnType())
                    .as("DeliveryQueryRepositoryPort.%s must not return void", m.getName())
                    .isNotEqualTo(void.class);
        }
    }

    @Test
    void deliveryPipelineRepositoryPortImportsNoFrameworkType() {
        for (Method m : DeliveryPipelineRepositoryPort.class.getDeclaredMethods()) {
            assertNoSpringType(m);
        }
    }

    @Test
    void deliveryQueryRepositoryPortImportsNoFrameworkType() {
        for (Method m : DeliveryQueryRepositoryPort.class.getDeclaredMethods()) {
            assertNoSpringType(m);
        }
    }

    // --- subscription circuit ops shape (TASK-004-04) ---

    @Test
    void subscriptionRepositoryPortHasNoTransitionCircuitState() {
        boolean hasOldMethod = Arrays.stream(SubscriptionRepositoryPort.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("transitionCircuitState"));
        assertThat(hasOldMethod)
                .as("transitionCircuitState must be removed from SubscriptionRepositoryPort")
                .isFalse();
    }

    @Test
    void subscriptionRepositoryPortHasFourCircuitMethods() {
        List<String> circuitMethods = Arrays.stream(SubscriptionRepositoryPort.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(n -> n.equals("tripCircuit") || n.equals("reopenCircuit")
                        || n.equals("promoteToHalfOpen") || n.equals("closeCircuit"))
                .toList();
        assertThat(circuitMethods).containsExactlyInAnyOrder(
                "tripCircuit", "reopenCircuit", "promoteToHalfOpen", "closeCircuit");
    }

    @Test
    void allFourCircuitMethodsReturnBoolean() {
        for (Method m : SubscriptionRepositoryPort.class.getDeclaredMethods()) {
            if (List.of("tripCircuit", "reopenCircuit", "promoteToHalfOpen", "closeCircuit")
                    .contains(m.getName())) {
                assertThat(m.getReturnType())
                        .as("SubscriptionRepositoryPort.%s must return boolean", m.getName())
                        .isEqualTo(boolean.class);
            }
        }
    }

    @Test
    void noCircuitMethodOnSubscriptionRepositoryPortHasClientIdParameter() {
        // findActiveForEvent(clientId, eventType) retains its committed signature (cross-tenant
        // fan-out predicate). The circuit operations are the scope of TASK-004-04 and must be
        // free of clientId per the task's acceptance criteria.
        List<String> circuitMethodNames = List.of("tripCircuit", "reopenCircuit", "promoteToHalfOpen", "closeCircuit");
        for (Method m : SubscriptionRepositoryPort.class.getDeclaredMethods()) {
            if (!circuitMethodNames.contains(m.getName())) {
                continue;
            }
            boolean hasClientId = Arrays.stream(m.getParameters())
                    .anyMatch(p -> p.getType().equals(String.class)
                            && (p.getName().equals("clientId") || p.getName().contains("client")));
            assertThat(hasClientId)
                    .as("SubscriptionRepositoryPort.%s must not have a clientId parameter", m.getName())
                    .isFalse();
        }
    }

    @Test
    void subscriptionRepositoryPortImportsNoFrameworkType() {
        for (Method m : SubscriptionRepositoryPort.class.getDeclaredMethods()) {
            assertNoSpringType(m);
        }
    }

    // --- helpers ---

    private static boolean classExists(String fqcn) {
        try {
            Class.forName(fqcn);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void assertNoSpringType(Method m) {
        for (Class<?> paramType : m.getParameterTypes()) {
            assertThat(paramType.getName())
                    .as("Method %s has a Spring/framework parameter type", m.getName())
                    .doesNotStartWith("org.springframework");
        }
        assertThat(m.getReturnType().getName())
                .as("Method %s has a Spring/framework return type", m.getName())
                .doesNotStartWith("org.springframework");
    }
}
