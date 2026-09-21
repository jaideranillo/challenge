package com.cobre.challenge.application.usecase;

import com.cobre.challenge.application.port.out.queue.NotificationQueuePort;
import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ADR-002 §1.1 step 5 / Amendment C3: publishes a {@link DeliveryPointer} only after the
 * enclosing transaction commits, and never on the request thread.
 *
 * <p>{@link #dispatch} registers an {@code afterCommit}-only {@link TransactionSynchronization}
 * — never {@code beforeCommit}, never {@code afterCompletion} with a status check — because
 * publishing a pointer to a row that might still roll back is the one ordering error ADR-001 §1
 * exists to prevent. The commit callback only submits the publish to {@link #executor} and
 * returns; the request thread never performs the {@code SendMessage} itself.
 *
 * <p>Every {@link Throwable} raised while publishing is caught inside the submitted task: it is
 * logged at warn by {@code delivery_id} only and counted, but never propagated. A lost publish is
 * invisible to the caller by design (the relay is the guaranteed path); the counter is what keeps
 * it visible to operations (OWASP A09/A10).
 *
 * <p>No {@code synchronized} anywhere on this path: guarding the submission or the publish call
 * would pin the calling virtual thread's carrier for a blocking network round trip.
 */
@Component
public class IngestPublishDispatcher {

    private static final Logger log = LoggerFactory.getLogger(IngestPublishDispatcher.class);
    private static final String PUBLISH_FAILED_COUNTER = "notification.ingest.publish.failed";

    private final NotificationQueuePort queuePort;
    private final Counter publishFailedCounter;
    private final ExecutorService executor;

    @Autowired
    public IngestPublishDispatcher(NotificationQueuePort queuePort, MeterRegistry meterRegistry) {
        this(queuePort, meterRegistry, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Test seam: lets a test pass a same-thread executor for deterministic assertions. */
    IngestPublishDispatcher(NotificationQueuePort queuePort, MeterRegistry meterRegistry, ExecutorService executor) {
        this.queuePort = queuePort;
        // No client_id/subscription_id tag (ADR-002 Q8): either would be unbounded label cardinality.
        this.publishFailedCounter = meterRegistry.counter(PUBLISH_FAILED_COUNTER);
        this.executor = executor;
    }

    public void dispatch(DeliveryPointer pointer) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.submit(() -> publish(pointer));
            }
        });
    }

    private void publish(DeliveryPointer pointer) {
        try {
            queuePort.publish(pointer);
        } catch (Throwable t) {
            publishFailedCounter.increment();
            log.warn("Publish failed for delivery_id={}", pointer.deliveryId(), t);
        }
    }
}
