package com.cobre.challenge.adapter.in.web.local.eventgenerator.dto;

import java.util.List;
import java.util.UUID;

/** Synthetic events just registered, in emission order, with the client each was assigned to. */
public record GenerateEventsResponse(int requested, int generated, List<GeneratedEvent> events) {

    /**
     * {@code eventId} is the platform event's own id (not what the self-service API's URL takes).
     * {@code deliveryIds} is what {@code GET /notification_events/{notification_event_id}} and
     * {@code POST .../replay} actually take — one per subscription the event fanned out to (ADR-002
     * §1.1 step 3), empty if no active/verified subscription matched (event stored, no delivery
     * created, ADR-002 §1.1 "zero matches" case). {@code clientId} is what you need to mint a
     * matching token for (`POST /local/dev-token`) before either id is queryable.
     */
    public record GeneratedEvent(String eventId, List<UUID> deliveryIds, String clientId, String eventType) {}
}
