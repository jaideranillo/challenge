package com.cobre.challenge.adapter.in.web.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cobre.challenge.application.port.in.pipeline.RegisterNotificationEventUseCase;
import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventCommand;
import com.cobre.challenge.application.port.in.pipeline.dto.RegisterNotificationEventResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// addFilters = false: no SecurityFilterChain exists for this path (deliberate scope cut,
// docs/concerns.md "The ingest endpoint ships unauthenticated"), so the security auto-config
// filter chain is disabled here rather than testing an authorization posture this task does
// not own.
@WebMvcTest(controllers = EventIngestController.class)
@AutoConfigureMockMvc(addFilters = false)
class EventIngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterNotificationEventUseCase useCase;

    @Test
    void returns202WithDeliveryIdsAndNewlyCreatedTrueForANormalIngest() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        when(useCase.register(any(RegisterNotificationEventCommand.class)))
                .thenReturn(new RegisterNotificationEventResult(List.of(deliveryId), true));

        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveryIds[0]").value(deliveryId.toString()))
                .andExpect(jsonPath("$.newlyCreated").value(true));
    }

    @Test
    void returns202WithEmptyDeliveryIdsWhenNoSubscriptionMatches() throws Exception {
        when(useCase.register(any(RegisterNotificationEventCommand.class)))
                .thenReturn(new RegisterNotificationEventResult(List.of(), false));

        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveryIds").isEmpty())
                .andExpect(jsonPath("$.newlyCreated").value(false));
    }

    @Test
    void returns202WithNewlyCreatedFalseOnAReplay() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        when(useCase.register(any(RegisterNotificationEventCommand.class)))
                .thenReturn(new RegisterNotificationEventResult(List.of(deliveryId), false));

        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.newlyCreated").value(false));
    }

    @Test
    void returns400AndDoesNotCallTheUseCaseWhenEventIdIsMissing() throws Exception {
        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "clientId": "client-1",
                                  "eventType": "payment.created",
                                  "content": "{}",
                                  "occurredAt": "2026-09-20T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(useCase);
    }

    @Test
    void returns400WhenEventIdExceedsTheSizeCeiling() throws Exception {
        String tooLong = "E".repeat(129);
        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJsonWithEventId(tooLong)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns400WhenEventIdHasAnUnsafeCharacter() throws Exception {
        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJsonWithEventId("EVT 001;DROP")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns400WhenClientIdIsBlank() throws Exception {
        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "eventId": "EVT001",
                                  "clientId": "",
                                  "eventType": "payment.created",
                                  "content": "{}",
                                  "occurredAt": "2026-09-20T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns400WhenContentExceedsTheSizeCeiling() throws Exception {
        String tooLong = "x".repeat(65_537);
        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "eventId": "EVT001",
                                  "clientId": "client-1",
                                  "eventType": "payment.created",
                                  "content": "%s",
                                  "occurredAt": "2026-09-20T10:00:00Z"
                                }
                                """
                                        .formatted(tooLong)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns400WhenOccurredAtIsMissing() throws Exception {
        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "eventId": "EVT001",
                                  "clientId": "client-1",
                                  "eventType": "payment.created",
                                  "content": "{}"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsTheRequestToTheCommandExplicitly() throws Exception {
        when(useCase.register(any(RegisterNotificationEventCommand.class)))
                .thenReturn(new RegisterNotificationEventResult(List.of(), false));

        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isAccepted());

        verify(useCase)
                .register(new RegisterNotificationEventCommand(
                        "EVT001",
                        "client-1",
                        "payment.created",
                        "{}",
                        java.time.Instant.parse("2026-09-20T10:00:00Z")));
    }

    private static String validRequestJson() {
        return requestJsonWithEventId("EVT001");
    }

    private static String requestJsonWithEventId(String eventId) {
        return """
                {
                  "eventId": "%s",
                  "clientId": "client-1",
                  "eventType": "payment.created",
                  "content": "{}",
                  "occurredAt": "2026-09-20T10:00:00Z"
                }
                """
                .formatted(eventId);
    }
}
