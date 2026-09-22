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
 * ADR-008 §4.1.1: DLQ <strong>depth</strong>, not arrival count. {@code
 * notification.delivery.dlq.arrival} counts messages as they land and resets on restart; this
 * gauge answers "how much is sitting in the DLQ right now" via a scheduled poll of {@code
 * GetQueueAttributes}. LocalStack SQS publishes no CloudWatch-style queue-depth metric, so the
 * application must produce it.
 *
 * <p>Placement: {@code adapter/out}, beside the other SQS adapters. This reads infrastructure
 * state and reports it to infrastructure - no use case, no port, no application-layer type. Tags:
 * none, the queue is fixed and singular.
 *
 * <p>Polls on the same cadence as the delivery relay ({@code challenge.relay.poll-interval}), the
 * "on the order of the relay poll interval or slower" ADR-008 §4.1.1 calls for -
 * {@code GetQueueAttributes} is a billed API call in real AWS and a depth that is minutes stale is
 * still actionable.
 *
 * <p>Failure behavior (A10): an SDK failure or timeout is caught, logged at {@code WARN} with the
 * exception message never echoed (ADR-008 §3.4 - only the exception's class/frames, per {@link
 * DlqDepthGauge#pollDepth()}), and never thrown out of the scheduled method or allowed to disturb
 * {@link com.cobre.challenge.adapter.in.messaging.DeliveryDlqConsumer}. The gauge keeps its last
 * known value, or {@code NaN} if it has never succeeded.
 *
 * <p>No {@code synchronized} anywhere: the backing value is a plain {@link AtomicLong}, read by
 * the meter registry off the scheduling thread with no lock shared with the delivery path.
 */
@Component
public class DlqDepthGauge {

    private static final Logger log = LoggerFactory.getLogger(DlqDepthGauge.class);
    private static final String GAUGE_NAME = "notification.delivery.dlq.depth";
    private static final double NEVER_SUCCEEDED = Double.NaN;

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final AtomicLong lastKnownDepth = new AtomicLong(Double.doubleToLongBits(NEVER_SUCCEEDED));

    public DlqDepthGauge(SqsClient sqsClient, SqsProperties sqsProperties, MeterRegistry meterRegistry) {
        this.sqsClient = sqsClient;
        this.queueUrl = sqsClient
                .getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(sqsProperties.queues().deliveriesDlq())
                        .build())
                .queueUrl();
        meterRegistry.gauge(GAUGE_NAME, this, DlqDepthGauge::currentDepth);
    }

    double currentDepth() {
        return Double.longBitsToDouble(lastKnownDepth.get());
    }

    /** Package-private test seam: drives exactly one poll cycle. */
    @Scheduled(fixedDelayString = "${challenge.relay.poll-interval}")
    void pollDepth() {
        try {
            String value = sqsClient
                    .getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(queueUrl)
                            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                            .build())
                    .attributes()
                    .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
            lastKnownDepth.set(Double.doubleToLongBits(Double.parseDouble(value)));
        } catch (Exception e) {
            // §3.4: log the exception class/frames only, never its message - an SDK exception can
            // echo a response body. The gauge keeps its last known value (or NaN).
            log.warn("DLQ depth poll failed, gauge unchanged: {}", e.getClass().getName());
        }
    }
}
