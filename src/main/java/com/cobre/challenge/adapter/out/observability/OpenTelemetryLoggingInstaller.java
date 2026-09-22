package com.cobre.challenge.adapter.out.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * logback-spring.xml declares the {@code OpenTelemetryAppender} statically, before the Spring
 * {@link OpenTelemetry} bean exists. It stays a no-op until {@link OpenTelemetryAppender#install}
 * hands it that bean, so log events before this listener fires are not exported - acceptable,
 * since this runs on {@link ApplicationReadyEvent}, before any request traffic.
 */
@Component
class OpenTelemetryLoggingInstaller implements ApplicationListener<ApplicationReadyEvent> {

    private final OpenTelemetry openTelemetry;

    OpenTelemetryLoggingInstaller(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
