# Error: Loki received zero logs despite correct OTLP config

**Date:** 2026-09-22

## Symptom

Traces (Tempo) and metrics (Prometheus) worked correctly via OTLP, but Loki had zero labels and
zero log lines no matter how much traffic ran. `management.logging.export.otlp.enabled: true` was
already set in `application.yaml` per ADR-008 §3.1, and datasource UIDs/provisioning were correct.

## Root cause

`spring-boot-starter-opentelemetry` (4.1.1) auto-configures the `SdkLoggerProvider` bean and the
OTLP log exporter (confirmed by decompiling `OpenTelemetryLoggingAutoConfiguration` /
`OtlpLoggingConfigurations$Exporters` — both exist and both wire up correctly, an
`OtlpHttpLogRecordExporter` bean does get created, `HttpSender` initializes at startup). But
**nothing bridges Logback log events into that SDK.** The starter does not include
`io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`, and there was no
`logback-spring.xml`. Every application log statement went to Logback's default console appender
only; none ever reached the `SdkLoggerProvider`, so the exporter had nothing to export — not even
an empty batch.

This is a real gap in the starter, not a config mistake: `management.logging.export.otlp.*`
properties only control the exporter/SDK side, not the Logback-to-SDK bridge.

## Fix (3 files)

1. `build.gradle` — added `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.16.0-alpha`.
2. `src/main/resources/logback-spring.xml` (new) — declares the `OpenTelemetryAppender`
   (`io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender`) alongside the
   default console appender on root, with `captureMdcAttribute` for `delivery_id`, `event_id`,
   `client_id`, `subscription_id` (the MDC keys ADR-008 §3.2 puts on every delivery-pipeline log line).
3. `src/main/java/.../adapter/out/observability/OpenTelemetryLoggingInstaller.java` (new) — an
   `ApplicationListener<ApplicationReadyEvent>` that calls `OpenTelemetryAppender.install(openTelemetry)`,
   handing the statically-declared Logback appender the Spring-managed `OpenTelemetry` bean. The
   appender is declared before that bean exists (logback config loads earlier than the Spring
   context), so it's a no-op until this listener fires — negligible, since it fires before any
   request traffic.

Verified: after the fix, `{service_name="challenge"}` in Loki returns real log lines with
`trace_id`/`span_id` attached automatically from the live OTel context.

## Detection

Found as blocker #2 during TASK-009-07 live verification; investigated by decompiling the
`spring-boot-opentelemetry-4.1.1.jar` classes (no source jars available) to confirm the exporter
bean chain was correct and the gap was specifically the missing Logback bridge.
