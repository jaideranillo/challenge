package com.cobre.challenge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.cobre.challenge.domain.model.tenant.TenantId;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.Method;
import java.util.Set;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** ADR-007 5.1: structural tenant-boundary rules, enforced at build time rather than by review. */
@AnalyzeClasses(packages = "com.cobre.challenge", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantBoundaryArchTest {

    private static final String SECURITY_PACKAGE = "com.cobre.challenge.adapter.in.web.security..";

    private static final Set<String> FORBIDDEN_TENANT_NAMES =
            Set.of("client_id", "clientId", "tenant", "tenant_id");

    private static final Set<Class<?>> TENANT_BINDING_ANNOTATIONS =
            Set.of(PathVariable.class, RequestParam.class, RequestHeader.class);

    private static final Set<String> CLIENT_FACING_QUERY_PORTS =
            Set.of(
                    "DeliveryQueryRepositoryPort",
                    "NotificationEventQueryRepositoryPort",
                    "DeliveryAttemptQueryRepositoryPort");

    @ArchTest
    static final ArchRule only_authenticated_tenant_resolver_constructs_tenant_id =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(SECURITY_PACKAGE)
                    .should()
                    .callConstructor(TenantId.class, String.class)
                    .because(
                            "ADR-007 5.1 rule 1: TenantId's only production construction site is "
                                    + "AuthenticatedTenantResolver in adapter.in.web.security");

    @ArchTest
    static final ArchRule controller_parameters_never_bind_tenant_from_request =
            methods()
                    .should(notBindForbiddenTenantParameter())
                    .because("ADR-007 5.1 rule 2: the tenant is never accepted as request input");

    @ArchTest
    static final ArchRule application_and_domain_never_import_spring_security =
            noClasses()
                    .that()
                    .resideInAnyPackage("com.cobre.challenge.application..", "com.cobre.challenge.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.springframework.security..")
                    .because("ADR-007 5.1 rule 3: security types stop at the adapter boundary");

    @ArchTest
    static final ArchRule client_facing_query_ports_require_tenant_id_parameter =
            methods()
                    .that()
                    .areDeclaredInClassesThat(isClientFacingQueryPort())
                    .should(haveATenantIdParameter())
                    .because(
                            "ADR-007 5.2 / Amendment E1: every method on a client-facing query port takes a "
                                    + "TenantId, unlike the cross-tenant pipeline ports");

    private static DescribedPredicate<JavaClass> isClientFacingQueryPort() {
        return new DescribedPredicate<>("are client-facing query ports") {
            @Override
            public boolean test(JavaClass javaClass) {
                return CLIENT_FACING_QUERY_PORTS.contains(javaClass.getSimpleName());
            }
        };
    }

    private static ArchCondition<JavaMethod> notBindForbiddenTenantParameter() {
        return new ArchCondition<>("not bind client_id, clientId, tenant or tenant_id via "
                + "@PathVariable, @RequestParam or @RequestHeader") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Method reflected = method.reflect();
                for (JavaParameter parameter : method.getParameters()) {
                    String realName = reflected.getParameters()[parameter.getIndex()].getName();
                    for (Class<?> annotationType : TENANT_BINDING_ANNOTATIONS) {
                        parameter
                                .tryGetAnnotationOfType(annotationType.getName())
                                .ifPresent(
                                        annotation -> {
                                            String boundName = bindingValue(annotation);
                                            String effectiveName = boundName.isEmpty() ? realName : boundName;
                                            if (FORBIDDEN_TENANT_NAMES.contains(effectiveName)) {
                                                events.add(
                                                        SimpleConditionEvent.violated(
                                                                method,
                                                                String.format(
                                                                        "%s binds forbidden tenant parameter '%s' via @%s on %s",
                                                                        method.getFullName(),
                                                                        effectiveName,
                                                                        annotationType.getSimpleName(),
                                                                        method.getDescription())));
                                            }
                                        });
                    }
                }
            }
        };
    }

    private static String bindingValue(JavaAnnotation<JavaParameter> annotation) {
        String value = (String) annotation.get("value").orElse("");
        if (!value.isEmpty()) {
            return value;
        }
        return (String) annotation.get("name").orElse("");
    }

    private static ArchCondition<JavaMethod> haveATenantIdParameter() {
        return new ArchCondition<>("have a " + TenantId.class.getSimpleName() + " parameter") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean hasTenantId =
                        method.getRawParameterTypes().stream()
                                .map(JavaClass::getFullName)
                                .anyMatch(TenantId.class.getName()::equals);
                if (!hasTenantId) {
                    events.add(
                            SimpleConditionEvent.violated(
                                    method,
                                    method.getFullName() + " has no TenantId parameter"));
                }
            }
        };
    }
}
