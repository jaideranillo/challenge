package com.cobre.challenge.adapter.in.web.ingest.dto;

import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventResult;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /internal/events} response body. Carries only {@code deliveryIds} and
 * {@code newlyCreated} — no subscription details, no echo of {@code content}.
 */
public record IngestEventResponse(List<UUID> deliveryIds, boolean newlyCreated) {

    public static IngestEventResponse from(RegisterNotificationEventResult result) {
        return new IngestEventResponse(result.deliveryIds(), result.newlyCreated());
    }
}
