package com.cobre.challenge.adapter.out.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ADR-008 §4.3: {@code client_id} and {@code subscription_id} are never a tag on any meter -
 * trace and log dimensions only. This filter {@code deny}s (never strips) a meter carrying
 * either tag key, active in every profile, because a missing panel is noticed and a silently
 * merged series is not.
 *
 * <p>This is the backstop, not the control: the in-process cardinality cost of registering the
 * meter is already paid before this filter runs. The build-failing test is the control.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityMeterConfig {

    static final String CLIENT_ID_TAG = "client_id";
    static final String SUBSCRIPTION_ID_TAG = "subscription_id";

    @Bean
    MeterFilter denyUnboundedCardinalityTagsMeterFilter() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (id.getTag(CLIENT_ID_TAG) != null || id.getTag(SUBSCRIPTION_ID_TAG) != null) {
                    return MeterFilterReply.DENY;
                }
                return MeterFilterReply.NEUTRAL;
            }
        };
    }
}
