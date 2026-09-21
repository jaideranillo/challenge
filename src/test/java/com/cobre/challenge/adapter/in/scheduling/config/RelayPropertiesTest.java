package com.cobre.challenge.adapter.in.scheduling.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Proves {@code challenge.relay.*} binds to the ADR-002 §2.1 defaults declared in
 * {@code application.yaml}, not to values hardcoded in the record.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RelayPropertiesTest {

    @Autowired
    RelayProperties relayProperties;

    @Test
    void bindsAdr002DefaultsFromApplicationYaml() {
        assertThat(relayProperties.enabled()).isTrue();
        assertThat(relayProperties.pollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(relayProperties.batchLimit()).isEqualTo(500);
    }
}
