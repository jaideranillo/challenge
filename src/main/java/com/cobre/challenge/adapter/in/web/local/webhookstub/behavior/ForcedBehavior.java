package com.cobre.challenge.adapter.in.web.local.webhookstub.behavior;

import java.time.Duration;

/**
 * Internal control-plane state of the local webhook stub: the response
 * behavior currently forced for the {@code /receive} endpoint. Never
 * serialized.
 */
public sealed interface ForcedBehavior {

    record None() implements ForcedBehavior {}

    record Status(int code) implements ForcedBehavior {}

    record Hang(Duration duration) implements ForcedBehavior {

        // Thread.sleep parks the virtual thread without pinning its carrier
        // (no synchronized block, no lock held across the wait).
        public void await() {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static ForcedBehavior none() {
        return new None();
    }
}
