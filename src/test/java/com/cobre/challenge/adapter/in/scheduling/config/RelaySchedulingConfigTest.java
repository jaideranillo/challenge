package com.cobre.challenge.adapter.in.scheduling.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.challenge.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR-002 §2.1 / TASK-006-08: {@code challenge.relay.enabled} gates {@code @EnableScheduling}
 * itself, not just a future {@code @Scheduled} method. TASK-006-10/11 rely on the disabled case
 * to keep the acceptance tests' fixture rows from being raced by a live scheduler.
 */
class RelaySchedulingConfigTest {

    @Import(TestcontainersConfiguration.class)
    @SpringBootTest
    static class EnabledByDefault {

        @Autowired
        ApplicationContext context;

        @Test
        void schedulingInfrastructureIsRegisteredWhenEnabledFlagIsAbsentOrTrue() {
            assertThat(context.getBean(RelaySchedulingConfig.class)).isNotNull();
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class)).isNotNull();
        }
    }

    @Import(TestcontainersConfiguration.class)
    @SpringBootTest
    @TestPropertySource(properties = "challenge.relay.enabled=false")
    static class DisabledExplicitly {

        @Autowired
        ApplicationContext context;

        @Test
        void noSchedulingInfrastructureIsRegisteredWhenDisabled() {
            assertThatThrownBy(() -> context.getBean(RelaySchedulingConfig.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> context.getBean(ScheduledAnnotationBeanPostProcessor.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }
}
