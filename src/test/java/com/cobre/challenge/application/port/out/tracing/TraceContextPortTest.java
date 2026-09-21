package com.cobre.challenge.application.port.out.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TraceContextPortTest {

    @Test
    void traceContextPortIsAnInterface() {
        assertThat(TraceContextPort.class.isInterface()).isTrue();
    }

    @Test
    void hasExactlyOneMethod() {
        assertThat(TraceContextPort.class.getDeclaredMethods()).hasSize(1);
    }

    @Test
    void currentTraceparentReturnsOptionalOfString() throws NoSuchMethodException {
        Method method = TraceContextPort.class.getDeclaredMethod("currentTraceparent");

        assertThat(method.getReturnType()).isEqualTo(Optional.class);
        assertThat(method.getParameterCount()).isZero();
    }

    @Test
    void importsNoFrameworkType() {
        for (Method m : TraceContextPort.class.getDeclaredMethods()) {
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
}
