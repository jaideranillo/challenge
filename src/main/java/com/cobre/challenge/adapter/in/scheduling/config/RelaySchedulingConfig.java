package com.cobre.challenge.adapter.in.scheduling.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} for the relay; {@code enabled=false} lets acceptance tests drive the use case without a racing scheduler. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RelayProperties.class)
@ConditionalOnProperty(prefix = "challenge.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class RelaySchedulingConfig {
}
