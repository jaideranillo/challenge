package com.cobre.challenge.application.port.out.queue;

import com.cobre.challenge.application.port.out.queue.dto.DeliveryPointer;
import java.util.List;

/**
 * The single publisher adapter of ADR-004 SS1: both the gateway's
 * {@code SendMessage} and the relay's {@code SendMessageBatch} write the
 * same envelope through this one port.
 */
public interface NotificationQueuePort {

    void publish(DeliveryPointer pointer);

    void publishBatch(List<DeliveryPointer> pointers);
}
