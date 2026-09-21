package com.cobre.challenge.adapter.in.web.local.webhookstub;

import com.cobre.challenge.adapter.in.web.local.webhookstub.dto.RecordedRequest;
import com.cobre.challenge.adapter.in.web.local.webhookstub.behavior.ForcedBehavior;
import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Bounded, thread-safe in-memory record of requests received by the local
 * webhook stub, plus the currently forced response behavior. Local-demo
 * and local-test tooling only: never active outside the "local" profile.
 */
@Component
@Profile("local")
public class LocalWebhookStubRecorder {

    private static final int MAX_RECORDS = 200;

    private final Deque<RecordedRequest> records = new ConcurrentLinkedDeque<>();
    private final AtomicInteger recordCount = new AtomicInteger(0);
    private final AtomicReference<ForcedBehavior> forcedBehavior = new AtomicReference<>(ForcedBehavior.none());

    public void record(RecordedRequest request) {
        records.addFirst(request);
        if (recordCount.incrementAndGet() > MAX_RECORDS) {
            records.pollLast();
            recordCount.decrementAndGet();
        }
    }

    public List<RecordedRequest> recentFirst() {
        return List.copyOf(records);
    }

    public void clearRecords() {
        records.clear();
        recordCount.set(0);
    }

    public void forceStatus(int statusCode) {
        forcedBehavior.set(new ForcedBehavior.Status(statusCode));
    }

    public void forceHang(Duration duration) {
        forcedBehavior.set(new ForcedBehavior.Hang(duration));
    }

    public void reset() {
        forcedBehavior.set(ForcedBehavior.none());
    }

    public ForcedBehavior currentBehavior() {
        return forcedBehavior.get();
    }
}
