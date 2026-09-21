---
id: TASK-007-09
feature: FEAT-007
title: Outbound webhook envelope, serializer port and Jackson adapter
status: Ready for Review
agent: backend-engineer
depends_on: []
date: 2026-09-21
---

# TASK-007-09: Outbound envelope and its serializer

## Feature

FEAT-007

## Assigned Agent

`backend-engineer`

## Scope

- File(s):
  - `src/main/java/com/cobre/challenge/application/port/out/webhook/dto/WebhookEnvelope.java` (new)
  - `src/main/java/com/cobre/challenge/application/port/out/webhook/WebhookEnvelopeSerializerPort.java` (new)
  - `src/main/java/com/cobre/challenge/adapter/out/webhook/JacksonWebhookEnvelopeSerializer.java` (new)
- Concern: ADR-004 §1.1's body, as a record, and the one place it becomes a string.

### The envelope (ADR-004 §1.1, verbatim)

```json
{
  "notification_event_id": "...",   // deliveries.delivery_id
  "event_id": "EVT001",
  "event_type": "credit_card_payment",
  "client_id": "CLIENT001",
  "created_at": "2024-03-15T09:30:22.145231Z",   // notification_events.created_at
  "attempt": 2,
  "content": "..."                                // the platform event body, verbatim
}
```

Seven fields, snake_case on the wire, in this order. `notification_event_id` is the
`delivery_id` (ADR-003 §3's naming note) — the JSON key is **not** `delivery_id`, and this is the
one place the two names diverge, so get it right. `created_at` rides in the body, not a header,
and that is a security property (ADR-004 §1.1): it is covered by the signature only because it is
in the body.

- Record components in Java camelCase; map to snake_case with `@JsonProperty` on the record, the
  same way the merged `NotificationEnvelope` handles its wire shape.
- `attempt` is the attempt number **of this attempt**, i.e. the authoritative `attempt_count`
  from the row plus one. The caller computes it; the record just carries it.
- Validate in the compact constructor: every field non-null, `attempt >= 1`.

### Why the serializer is a port

The HMAC covers the body bytes, so the exact string that is signed must be the exact string that
is sent. If the HTTP adapter serialized the record itself, the use case would be signing one
rendering and the adapter sending another — a field-order or whitespace difference would break
every client's verification silently. The use case therefore holds the string:

```java
// application/port/out/webhook/WebhookEnvelopeSerializerPort
/** The exact bytes that are both signed and sent. */
String serialize(WebhookEnvelope envelope);
```

`JacksonWebhookEnvelopeSerializer` implements it with the injected `ObjectMapper` bean (the merged
`SqsNotificationQueueAdapter` already injects it the same way). No pretty printing, no
`ObjectMapper` of its own, no mutation of the shared mapper's configuration.

## Out of Scope

- Signing (TASK-007-08) and header construction (TASK-007-14).
- The inbound pointer envelope (`adapter/out/messaging/dto/NotificationEnvelope`) — a different
  message, merged, untouched.
- Loading the event or delivery rows. The caller passes values in.

## Testing (phase rule — read before writing any test)

Unit tests only in FEAT-007: plain JUnit, no Spring context, no Testcontainers, no LocalStack, no
Docker. Construct the serializer with a plain `new ObjectMapper()` (or the project's Jackson 3
equivalent, matching the merged adapter's import) in the test.

Required unit scenarios:
- the serialized JSON contains exactly the seven keys above, in snake_case, and `notification_event_id`
  carries the delivery id
- `created_at` serializes as an ISO-8601 instant with UTC offset, not an epoch number
- `content` is emitted verbatim, including when it contains JSON-looking text (it is a string
  field, never a nested document)
- serializing the same envelope twice produces byte-identical output — the property the whole
  port exists for
- the compact constructor rejects a null field and `attempt = 0`

**Do not run `./gradlew test` or `./gradlew build`.** Verify with `./gradlew compileJava
compileTestJava` and report the tests as written and pending the Tech Lead's later explicit run.

## Acceptance Criteria

- [ ] Exactly the seven ADR-004 §1.1 fields, in order, snake_case on the wire.
- [ ] `notification_event_id` carries `delivery_id`; no key named `delivery_id` appears in the body.
- [ ] `created_at` comes from the event row, never from the delivery row or a clock.
- [ ] The port returns `String`; the HTTP adapter never serializes the record.
- [ ] The serializer uses the injected `ObjectMapper` and mutates no shared configuration.
- [ ] Repeated serialization is byte-identical.
- [ ] Every unit scenario listed above is covered by a plain JUnit test.
- [ ] Follows SOLID / YAGNI / Effective Java rules from the agent's own definition.
- [ ] No new OWASP Top 10:2025 exposure introduced (or explicitly flagged to security-engineer).

## Definition of Done

Code written, tests written but **not run**. **Do not run `git add` or `git commit`.** Set this
task's `status` to `Ready for Review` and stop.

## Status

Ready for Review <!-- Not Started | In Progress | Ready for Review | Done (Done is set by the user only) -->
