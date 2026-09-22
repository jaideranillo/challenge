package com.cobre.challenge.adapter.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cobre.challenge.adapter.in.scheduling.config.RelayProperties;
import com.cobre.challenge.application.port.in.pipeline.DispatchPendingDeliveriesUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesResult;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveryRelaySchedulerTest {

    private final RelayProperties relayProperties = new RelayProperties(true, Duration.ofSeconds(5), 500);
    private final DispatchSpanRecorder dispatchSpanRecorder = new DispatchSpanRecorder(Tracer.NOOP, Propagator.NOOP);

    @Test
    void pollOnce_buildsCommand_fromBatchLimit_andCapturesAsOfOnce() {
        RecordingUseCase useCase = new RecordingUseCase();
        DeliveryRelayScheduler scheduler =
                new DeliveryRelayScheduler(useCase, relayProperties, Tracer.NOOP, dispatchSpanRecorder);

        scheduler.pollOnce();

        assertThat(useCase.receivedCommands).hasSize(1);
        assertThat(useCase.receivedCommands.get(0).batchLimit()).isEqualTo(500);
    }

    @Test
    void throwingUseCase_doesNotPropagate_andSubsequentCycleStillRuns() {
        ThrowsOnceUseCase useCase = new ThrowsOnceUseCase();
        DeliveryRelayScheduler scheduler =
                new DeliveryRelayScheduler(useCase, relayProperties, Tracer.NOOP, dispatchSpanRecorder);

        assertThatCode(scheduler::pollOnce).doesNotThrowAnyException();
        assertThatCode(scheduler::pollOnce).doesNotThrowAnyException();

        assertThat(useCase.callCount).isEqualTo(2);
    }

    private static class RecordingUseCase implements DispatchPendingDeliveriesUseCase {
        List<DispatchPendingDeliveriesCommand> receivedCommands = new ArrayList<>();

        @Override
        public DispatchPendingDeliveriesResult dispatch(DispatchPendingDeliveriesCommand command) {
            receivedCommands.add(command);
            return new DispatchPendingDeliveriesResult(0, 0, List.of());
        }
    }

    private static class ThrowsOnceUseCase implements DispatchPendingDeliveriesUseCase {
        int callCount;

        @Override
        public DispatchPendingDeliveriesResult dispatch(DispatchPendingDeliveriesCommand command) {
            callCount++;
            if (callCount == 1) {
                throw new IllegalStateException("boom");
            }
            return new DispatchPendingDeliveriesResult(0, 0, List.of());
        }
    }
}
