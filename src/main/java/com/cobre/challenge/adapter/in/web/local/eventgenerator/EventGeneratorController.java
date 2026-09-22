package com.cobre.challenge.adapter.in.web.local.eventgenerator;

import com.cobre.challenge.adapter.in.web.ingest.dto.IngestEventRequest;
import com.cobre.challenge.adapter.in.web.local.eventgenerator.dto.GenerateEventsRequest;
import com.cobre.challenge.adapter.in.web.local.eventgenerator.dto.GenerateEventsResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Local-only synthetic event generator, shaped after {@code docs/challenge/notification_events.json}.
 * Emits {@code count} events through a real HTTP call to {@code POST /internal/events} — the same
 * gateway-ingest step (step 1, ADR-002 §1.1) a real producer would hit — instead of calling the
 * use case in process, so a demo shows the whole pipeline, not a shortcut around it.
 *
 * <p>Reachable itself only because of {@link LocalEventGeneratorSecurityConfig}; the outbound call
 * it makes only succeeds because of {@link LocalIngestBypassSecurityConfig}, which is the actual
 * bypass — see that class's javadoc for why it exists and what it is not.
 */
@RestController
@RequestMapping("/local/event-generator")
class EventGeneratorController {

    private record Template(String eventType, String content) {}

    private static final List<Template> TEMPLATES =
            List.of(
                    new Template("credit_card_payment", "Credit card payment received for $150.00"),
                    new Template("debit_card_withdrawal", "ATM withdrawal of $200.00"),
                    new Template("credit_transfer", "Bank transfer received from Account #4567 for $1,500.00"),
                    new Template("debit_automatic_payment", "Monthly utility bill payment of $85.50"),
                    new Template("credit_refund", "Refund processed for order #789 for $45.99"),
                    new Template("debit_transfer", "Money transfer sent to Account #8901 for $500.00"),
                    new Template("credit_deposit", "Direct deposit received from Employer XYZ for $2,500.00"),
                    new Template("debit_purchase", "Point of sale purchase at Store ABC for $75.25"),
                    new Template("credit_cashback", "Cashback reward credited for $25.00"),
                    new Template("debit_subscription", "Monthly streaming service payment of $14.99"));

    private static final List<String> CLIENT_IDS = List.of("CLIENT001", "CLIENT002", "CLIENT003");

    private final RestClient restClient;

    EventGeneratorController(@Value("${server.port:8080}") int serverPort) {
        this.restClient = RestClient.builder().baseUrl("http://localhost:" + serverPort).build();
    }

    @PostMapping("/generate")
    ResponseEntity<GenerateEventsResponse> generate(@Valid @RequestBody GenerateEventsRequest request) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<String> eventIds = new ArrayList<>(request.count());
        for (int i = 0; i < request.count(); i++) {
            Template template = TEMPLATES.get(random.nextInt(TEMPLATES.size()));
            String clientId = CLIENT_IDS.get(random.nextInt(CLIENT_IDS.size()));
            String eventId = "EVT-" + UUID.randomUUID();
            restClient
                    .post()
                    .uri("/internal/events")
                    .body(
                            new IngestEventRequest(
                                    eventId, clientId, template.eventType(), template.content(), Instant.now()))
                    .retrieve()
                    .toBodilessEntity();
            eventIds.add(eventId);
        }
        return ResponseEntity.ok(new GenerateEventsResponse(request.count(), eventIds.size(), eventIds));
    }
}
