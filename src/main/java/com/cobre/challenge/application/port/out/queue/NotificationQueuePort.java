package com.cobre.challenge.application.port.out.queue;

import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import com.cobre.challenge.application.port.out.queue.dto.PublishBatchResult;
import java.util.List;

/** Single publisher adapter (ADR-004 SS1) for both {@code SendMessage} and {@code SendMessageBatch}. */
public interface NotificationQueuePort {

    void publish(DeliveryPointer pointer);

    /** Partially fallible; failed ids are observability-only, recovered via the already-pushed next_attempt_at (ADR-002 SS2.1). */
    PublishBatchResult publishBatch(List<DeliveryPointer> pointers);
}
