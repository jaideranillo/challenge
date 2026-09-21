package com.cobre.challenge.adapter.out.secrets.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookSecretPropertiesTest {

	@Test
	void defensivelyCopiesTheMapPassedAtConstruction() {
		Map<String, String> mutableSource = new HashMap<>(Map.of("ref-1", "top-secret"));
		WebhookSecretProperties properties = new WebhookSecretProperties(mutableSource);

		mutableSource.put("ref-1", "tampered");
		mutableSource.put("ref-2", "injected-after-construction");

		assertThat(properties.secrets()).containsExactly(Map.entry("ref-1", "top-secret"));
	}

	@Test
	void secretsAccessorReturnsAnImmutableCopy() {
		WebhookSecretProperties properties = new WebhookSecretProperties(Map.of("ref-1", "top-secret"));

		assertThat(properties.secrets()).isNotSameAs(properties.secrets());
	}

	@Test
	void toStringContainsNoSecretValue() {
		WebhookSecretProperties properties = new WebhookSecretProperties(Map.of("ref-1", "top-secret"));

		assertThat(properties.toString()).doesNotContain("top-secret");
	}

	@Test
	void nullMapAtConstructionYieldsEmptySecrets() {
		WebhookSecretProperties properties = new WebhookSecretProperties(null);

		assertThat(properties.secrets()).isEmpty();
	}
}
