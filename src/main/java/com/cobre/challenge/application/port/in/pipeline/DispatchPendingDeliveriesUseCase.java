package com.cobre.challenge.application.port.in.pipeline;

import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.DispatchPendingDeliveriesResult;

/**
 * The relay cycle (ADR-002 SS2.1): claims due deliveries and publishes
 * pointer messages.
 */
public interface DispatchPendingDeliveriesUseCase {

    DispatchPendingDeliveriesResult dispatch(DispatchPendingDeliveriesCommand command);
}
