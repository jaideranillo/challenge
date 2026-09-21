package com.cobre.challenge.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cobre.challenge.application.port.out.queue.NotificationQueuePort;
import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class IngestPublishDispatcherTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void openTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void closeTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void doesNotPublishBeforeCommit() {
        AtomicInteger publishCount = new AtomicInteger();
        IngestPublishDispatcher dispatcher = new IngestPublishDispatcher(
                new FakeQueuePort(p -> publishCount.incrementAndGet()), meterRegistry, new SameThreadExecutorService());

        dispatcher.dispatch(pointer());

        assertThat(publishCount.get()).isZero();
    }

    @Test
    void publishesAfterCommitOffTheRequestThreadViaTheExecutor() {
        AtomicInteger publishCount = new AtomicInteger();
        SameThreadExecutorService executor = new SameThreadExecutorService();
        IngestPublishDispatcher dispatcher = new IngestPublishDispatcher(
                new FakeQueuePort(p -> publishCount.incrementAndGet()), meterRegistry, executor);

        dispatcher.dispatch(pointer());
        triggerAfterCommit();

        assertThat(publishCount.get()).isEqualTo(1);
        assertThat(executor.executeCount.get()).isEqualTo(1);
    }

    @Test
    void aFailingPublishDoesNotPropagateAndIncrementsTheCounter() {
        IngestPublishDispatcher dispatcher = new IngestPublishDispatcher(
                new FakeQueuePort(p -> {
                    throw new RuntimeException("SQS is down");
                }),
                meterRegistry,
                new SameThreadExecutorService());

        assertThatCode(() -> {
                    dispatcher.dispatch(pointer());
                    triggerAfterCommit();
                })
                .doesNotThrowAnyException();

        assertThat(meterRegistry.get("notification.ingest.publish.failed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void theFailureLogCarriesNoPayloadField() {
        Logger dispatcherLogger = (Logger) LoggerFactory.getLogger(IngestPublishDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        dispatcherLogger.addAppender(appender);

        try {
            IngestPublishDispatcher dispatcher = new IngestPublishDispatcher(
                    new FakeQueuePort(p -> {
                        throw new RuntimeException("SQS is down");
                    }),
                    meterRegistry,
                    new SameThreadExecutorService());

            dispatcher.dispatch(pointer());
            triggerAfterCommit();

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).doesNotContain("content").contains("delivery_id");
        } finally {
            dispatcherLogger.detachAppender(appender);
        }
    }

    private static void triggerAfterCommit() {
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    private static DeliveryPointer pointer() {
        return new DeliveryPointer(UUID.randomUUID(), UUID.randomUUID(), 0, Optional.empty());
    }

    /** Runs every submitted task synchronously, on the calling thread, for deterministic assertions. */
    private static final class SameThreadExecutorService extends AbstractExecutorService {
        private final AtomicInteger executeCount = new AtomicInteger();
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            executeCount.incrementAndGet();
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    private static final class FakeQueuePort implements NotificationQueuePort {
        private final Consumer<DeliveryPointer> onPublish;

        FakeQueuePort(Consumer<DeliveryPointer> onPublish) {
            this.onPublish = onPublish;
        }

        @Override
        public void publish(DeliveryPointer pointer) {
            onPublish.accept(pointer);
        }

        @Override
        public void publishBatch(List<DeliveryPointer> pointers) {
            pointers.forEach(this::publish);
        }
    }
}
