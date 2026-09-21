package com.cobre.challenge.adapter.out.webhook.config;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code challenge.egress.allowed-hosts}: hosts exempt from {@code OutboundUrlValidator}'s
 * private/reserved-range check, empty by default. Under the {@code local} profile, an entry here
 * also waives the HTTPS requirement (see {@code OutboundUrlValidator}); every other profile keeps
 * HTTPS mandatory regardless of this list. Populated only in {@code application-local.yaml};
 * {@code application.yaml} carries no {@code challenge.egress} key of any kind.
 */
@ConfigurationProperties("challenge.egress")
public record EgressProperties(List<String> allowedHosts) {

    public EgressProperties {
        allowedHosts = List.copyOf(Objects.requireNonNullElseGet(allowedHosts, List::of));
    }
}
