package com.cobre.challenge.application.port.in.pipeline;

import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventResult;

/**
 * Gateway ingest (ADR-002 SS1.1): registers a platform event and fans it out
 * to matching subscriptions' {@code deliveries} rows.
 */
public interface RegisterNotificationEventUseCase {

    RegisterNotificationEventResult register(RegisterNotificationEventCommand command);
}
