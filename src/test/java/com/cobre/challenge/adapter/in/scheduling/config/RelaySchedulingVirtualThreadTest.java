package com.cobre.challenge.adapter.in.scheduling.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * Verifies, functionally rather than by assumption, that {@code spring.threads.virtual.enabled=true}
 * (already set in {@code application.yaml}) makes Spring Boot back {@code @Scheduled} with a
 * virtual-thread {@link SimpleAsyncTaskScheduler}, per TASK-006-08's mandate: "do not assume it".
 *
 * <p>{@code TaskSchedulingConfigurations.SimpleAsyncTaskSchedulerBuilderConfiguration} is the
 * Spring Boot autoconfiguration class that switches the default {@link TaskScheduler} to
 * {@link SimpleAsyncTaskScheduler} with virtual threads on when this flag is set; this test proves
 * the effect (bean type and the actual carrier thread) rather than reading that source.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RelaySchedulingVirtualThreadTest {

    @Autowired
    TaskScheduler taskScheduler;

    @Test
    void schedulerBeanIsSimpleAsyncTaskSchedulerNotAHandRolledFixedPool() {
        assertThat(taskScheduler).isInstanceOf(SimpleAsyncTaskScheduler.class);
    }

    @Test
    void scheduledTaskActuallyRunsOnAVirtualThread() throws InterruptedException {
        BlockingQueue<Boolean> isVirtual = new ArrayBlockingQueue<>(1);

        taskScheduler.schedule(() -> isVirtual.offer(Thread.currentThread().isVirtual()), Instant.now());

        Boolean ranOnVirtualThread = isVirtual.poll(5, TimeUnit.SECONDS);
        assertThat(ranOnVirtualThread).isTrue();
    }
}
