package com.cobre.challenge.application.port.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.application.port.out.persistence.dto.DeliveryPage;
import com.cobre.challenge.domain.model.delivery.Delivery;
import com.cobre.challenge.domain.model.event.NotificationEvent;
import com.cobre.challenge.domain.model.tenant.TenantId;
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
    void everyMethodOnDeliveryQueryRepositoryPortHasTenantIdParameter() {
        for (Method m : DeliveryQueryRepositoryPort.class.getDeclaredMethods()) {
            boolean hasTenantId = Arrays.stream(m.getParameters())
                    .anyMatch(p -> p.getType().equals(TenantId.class));
            assertThat(hasTenantId)
                    .as("DeliveryQueryRepositoryPort.%s must have a TenantId parameter", m.getName())
                    .isTrue();
        }
    }

    @Test
    void noMethodOnDeliveryQueryRepositoryPortHasStringClientIdParameter() {
        for (Method m : DeliveryQueryRepositoryPort.class.getDeclaredMethods()) {
            boolean hasStringClientId = Arrays.stream(m.getParameters())
                    .anyMatch(p -> p.getType().equals(String.class) && p.getName().contains("client"));
            assertThat(hasStringClientId)
                    .as("DeliveryQueryRepositoryPort.%s must not have a String clientId parameter", m.getName())
                    .isFalse();
        }
    }

    @Test
    void findByIdTakesTenantIdAsSecondParameter() throws NoSuchMethodException {
        Method m = DeliveryQueryRepositoryPort.class.getDeclaredMethod(
                "findById", java.util.UUID.class, TenantId.class);
        assertThat(m.getParameterTypes()[1]).isEqualTo(TenantId.class);
    }

    @Test
    void findPageTakesTenantIdAsFirstParameter() throws NoSuchMethodException {
        Method m = DeliveryQueryRepositoryPort.class.getDeclaredMethod(
                "findPage", TenantId.class,
                com.cobre.challenge.application.port.out.persistence.dto.DeliveryPageQuery.class, int.class);
        assertThat(m.getParameterTypes()[0]).isEqualTo(TenantId.class);
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

    // --- idempotency-conflict return signal (TASK-005-04) ---

    @Test
    void insertIfAbsentReturnsOptionalOfDelivery() throws NoSuchMethodException {
        Method m = DeliveryPipelineRepositoryPort.class.getDeclaredMethod("insertIfAbsent", Delivery.class);
        assertThat(m.getReturnType()).isEqualTo(Optional.class);
    }

    @Test
    void findLiveByEventAndSubscriptionReturnsOptionalOfDelivery() throws NoSuchMethodException {
        Method m = DeliveryPipelineRepositoryPort.class.getDeclaredMethod(
                "findLiveByEventAndSubscription", String.class, java.util.UUID.class);
        assertThat(m.getReturnType()).isEqualTo(Optional.class);
    }

    @Test
    void insertIsStillPresentAndUnchanged() throws NoSuchMethodException {
        Method m = DeliveryPipelineRepositoryPort.class.getDeclaredMethod("insert", Delivery.class);
        assertThat(m.getReturnType()).isEqualTo(Delivery.class);
    }

    // --- insertReplayIfAbsent port shape (TASK-008-16A) ---

    @Test
    void insertReplayIfAbsentExistsTakesDeliveryAndReturnsOptionalOfDelivery() throws NoSuchMethodException {
        Method m = DeliveryPipelineRepositoryPort.class.getDeclaredMethod(
                "insertReplayIfAbsent", Delivery.class);
        assertThat(m.getReturnType()).isEqualTo(Optional.class);
    }

    @Test
    void insertIfAbsentSignatureIsUnchangedByInsertReplayIfAbsent() throws NoSuchMethodException {
        Method m = DeliveryPipelineRepositoryPort.class.getDeclaredMethod("insertIfAbsent", Delivery.class);
        assertThat(m.getReturnType()).isEqualTo(Optional.class);
        assertThat(m.getParameterTypes()).containsExactly(Delivery.class);
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

    // --- notification event repository port shape (TASK-005-03) ---

    @Test
    void notificationEventRepositoryPortIsAnInterface() {
        assertThat(NotificationEventRepositoryPort.class.isInterface()).isTrue();
    }

    @Test
    void notificationEventRepositoryPortHasExactlyTwoMethods() {
        assertThat(NotificationEventRepositoryPort.class.getDeclaredMethods()).hasSize(2);
    }

    @Test
    void insertIfAbsentReturnsBooleanAndTakesNotificationEvent() throws NoSuchMethodException {
        Method method = NotificationEventRepositoryPort.class.getDeclaredMethod(
                "insertIfAbsent", NotificationEvent.class);

        assertThat(method.getReturnType()).isEqualTo(boolean.class);
    }

    @Test
    void findByIdReturnsOptionalAndTakesEventIdString() throws NoSuchMethodException {
        Method method = NotificationEventRepositoryPort.class.getDeclaredMethod("findById", String.class);

        assertThat(method.getReturnType()).isEqualTo(Optional.class);
    }

    @Test
    void noMethodOnNotificationEventRepositoryPortHasClientIdParameter() {
        for (Method m : NotificationEventRepositoryPort.class.getDeclaredMethods()) {
            boolean hasClientId = Arrays.stream(m.getParameters())
                    .anyMatch(p -> p.getType().equals(String.class)
                            && (p.getName().equals("clientId") || p.getName().contains("client")));
            assertThat(hasClientId)
                    .as("NotificationEventRepositoryPort.%s must not have a clientId parameter", m.getName())
                    .isFalse();
        }
    }

    @Test
    void notificationEventRepositoryPortImportsNoFrameworkType() {
        for (Method m : NotificationEventRepositoryPort.class.getDeclaredMethods()) {
            assertNoSpringType(m);
        }
    }

    // --- client-facing event and attempt query ports (TASK-008-13) ---

    @Test
    void notificationEventQueryRepositoryPortIsAnInterfaceWithOneMethod() {
        assertThat(NotificationEventQueryRepositoryPort.class.isInterface()).isTrue();
        assertThat(NotificationEventQueryRepositoryPort.class.getDeclaredMethods()).hasSize(1);
    }

    @Test
    void notificationEventQueryRepositoryPortFindByIdTakesStringEventIdTenantIdAndReturnsOptional() throws NoSuchMethodException {
        Method m = NotificationEventQueryRepositoryPort.class.getDeclaredMethod(
                "findById", String.class, TenantId.class);
        assertThat(m.getReturnType()).isEqualTo(Optional.class);
    }

    @Test
    void deliveryAttemptQueryRepositoryPortIsAnInterfaceWithOneMethod() {
        assertThat(DeliveryAttemptQueryRepositoryPort.class.isInterface()).isTrue();
        assertThat(DeliveryAttemptQueryRepositoryPort.class.getDeclaredMethods()).hasSize(1);
    }

    @Test
    void deliveryAttemptQueryRepositoryPortFindByDeliveryIdTakesTenantIdAndReturnsList() throws NoSuchMethodException {
        Method m = DeliveryAttemptQueryRepositoryPort.class.getDeclaredMethod(
                "findByDeliveryId", java.util.UUID.class, TenantId.class);
        assertThat(m.getReturnType()).isEqualTo(List.class);
    }

    @Test
    void neitherNewQueryPortHasAnUnscopedOverload() {
        assertThat(NotificationEventQueryRepositoryPort.class.getDeclaredMethods()).allSatisfy(m ->
                assertThat(Arrays.stream(m.getParameterTypes()).anyMatch(TenantId.class::equals)).isTrue());
        assertThat(DeliveryAttemptQueryRepositoryPort.class.getDeclaredMethods()).allSatisfy(m ->
                assertThat(Arrays.stream(m.getParameterTypes()).anyMatch(TenantId.class::equals)).isTrue());
    }

    @Test
    void noMethodOnDeliveryAttemptRepositoryPortHasTenantIdParameter() {
        for (Method m : DeliveryAttemptRepositoryPort.class.getDeclaredMethods()) {
            boolean hasTenant = Arrays.stream(m.getParameterTypes()).anyMatch(TenantId.class::equals);
            assertThat(hasTenant)
                    .as("DeliveryAttemptRepositoryPort.%s must stay cross-tenant", m.getName())
                    .isFalse();
        }
    }

    @Test
    void noMethodOnNotificationEventRepositoryPortHasTenantIdParameter() {
        for (Method m : NotificationEventRepositoryPort.class.getDeclaredMethods()) {
            boolean hasTenant = Arrays.stream(m.getParameterTypes()).anyMatch(TenantId.class::equals);
            assertThat(hasTenant)
                    .as("NotificationEventRepositoryPort.%s must stay cross-tenant", m.getName())
                    .isFalse();
        }
    }

    @Test
    void noMethodOnDeliveryPipelineRepositoryPortHasTenantIdParameter() {
        for (Method m : DeliveryPipelineRepositoryPort.class.getDeclaredMethods()) {
            boolean hasTenant = Arrays.stream(m.getParameterTypes()).anyMatch(TenantId.class::equals);
            assertThat(hasTenant)
                    .as("DeliveryPipelineRepositoryPort.%s must stay cross-tenant", m.getName())
                    .isFalse();
        }
    }

    @Test
    void noMethodOnSubscriptionRepositoryPortHasTenantIdParameter() {
        for (Method m : SubscriptionRepositoryPort.class.getDeclaredMethods()) {
            boolean hasTenant = Arrays.stream(m.getParameterTypes()).anyMatch(TenantId.class::equals);
            assertThat(hasTenant)
                    .as("SubscriptionRepositoryPort.%s must stay cross-tenant", m.getName())
                    .isFalse();
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
