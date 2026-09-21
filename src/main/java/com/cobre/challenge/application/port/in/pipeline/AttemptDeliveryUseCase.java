package com.cobre.challenge.application.port.in.pipeline;

import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.AttemptDeliveryResult;

/**
 * The worker, one pointer message per call (ADR-002 SS2.2). The command is a
 * pointer only; target URL, secret and content are loaded from the database
 * by the implementation.
 */
public interface AttemptDeliveryUseCase {

    AttemptDeliveryResult attempt(AttemptDeliveryCommand command);
}
