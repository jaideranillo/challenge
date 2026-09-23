package com.cobre.challenge.adapter.out.messaging;

import com.cobre.challenge.adapter.out.messaging.config.SqsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Same pattern as {@link DlqDepthGauge} (ADR-008 §4.1.1), applied to the main {@code deliveries}
 * queue instead of the DLQ: LocalStack SQS publishes no CloudWatch-style queue-depth metric, so
 * the application polls {@code GetQueueAttributes} and reports it as two gauges - messages
 * waiting to be received, and messages currently in flight (received, not yet deleted).
 *
 * <p>Not part of ADR-008's original scope (only the DLQ gauge was decided there); added on
 * request to give live visibility into the main queue the same way the DLQ already has it. Same
 * placement ({@code adapter/out}, no port, no use case), same polling cadence
 * ({@code challenge.relay.poll-interval}), and same fail-open behavior (A10): a poll failure is
 * logged at {@code WARN} with only the exception class, never its message, and never disturbs the
 * delivery pipeline.
 */
@Component
public class MainQueueDepthGauge {

    private static final Logger log = LoggerFactory.getLogger(MainQueueDepthGauge.class);
    private static final String VISIBLE_GAUGE_NAME = "notification.delivery.queue.depth";
    private static final String IN_FLIGHT_GAUGE_NAME = "notification.delivery.queue.in_flight";
    private static final double NEVER_SUCCEEDED = Double.NaN;

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final AtomicLong lastKnownVisible = new AtomicLong(Double.doubleToLongBits(NEVER_SUCCEEDED));
    private final AtomicLong lastKnownInFlight = new AtomicLong(Double.doubleToLongBits(NEVER_SUCCEEDED));

    public MainQueueDepthGauge(SqsClient sqsClient, SqsProperties sqsProperties, MeterRegistry meterRegistry) {
        this.sqsClient = sqsClient;
        this.queueUrl = sqsClient
                .getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(sqsProperties.queues().deliveries())
                        .build())
                .queueUrl();
        meterRegistry.gauge(VISIBLE_GAUGE_NAME, this, MainQueueDepthGauge::currentVisibleDepth);
        meterRegistry.gauge(IN_FLIGHT_GAUGE_NAME, this, MainQueueDepthGauge::currentInFlightDepth);
    }

    double currentVisibleDepth() {
        return Double.longBitsToDouble(lastKnownVisible.get());
    }

    double currentInFlightDepth() {
        return Double.longBitsToDouble(lastKnownInFlight.get());
    }

    /** Package-private test seam: drives exactly one poll cycle. */
    @Scheduled(fixedDelayString = "${challenge.relay.poll-interval}")
    void pollDepth() {
        try {
            var attributes = sqsClient
                    .getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(queueUrl)
                            .attributeNames(
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
                            .build())
                    .attributes();
            lastKnownVisible.set(Double.doubleToLongBits(Double.parseDouble(
                    attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))));
            lastKnownInFlight.set(Double.doubleToLongBits(Double.parseDouble(
                    attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))));
        } catch (Exception e) {
            // Same §3.4 rule as DlqDepthGauge: exception class/frames only, never the message.
            log.warn("Main queue depth poll failed, gauges unchanged: {}", e.getClass().getName());
        }
    }
}
