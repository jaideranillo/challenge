package com.cobre.challenge.adapter.out.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.adapter.out.secrets.config.WebhookSecretProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfiguredWebhookSecretResolverTest {

	@Test
	void knownReferenceResolvesToItsConfiguredMaterial() {
		ConfiguredWebhookSecretResolver resolver =
				new ConfiguredWebhookSecretResolver(new WebhookSecretProperties(Map.of("ref-1", "top-secret")));

		assertThat(resolver.resolve("ref-1")).contains("top-secret");
	}

	@Test
	void unknownReferenceReturnsEmptyWithoutThrowing() {
		ConfiguredWebhookSecretResolver resolver =
				new ConfiguredWebhookSecretResolver(new WebhookSecretProperties(Map.of("ref-1", "top-secret")));

		assertThat(resolver.resolve("does-not-exist")).isEmpty();
	}

	@Test
	void nullReferenceReturnsEmptyWithoutThrowing() {
		ConfiguredWebhookSecretResolver resolver =
				new ConfiguredWebhookSecretResolver(new WebhookSecretProperties(Map.of("ref-1", "top-secret")));

		assertThat(resolver.resolve(null)).isEmpty();
	}

	@Test
	void blankReferenceReturnsEmptyWithoutThrowing() {
		ConfiguredWebhookSecretResolver resolver =
				new ConfiguredWebhookSecretResolver(new WebhookSecretProperties(Map.of("ref-1", "top-secret")));

		assertThat(resolver.resolve("   ")).isEmpty();
	}

	@Test
	void mutatingTheCallersMapAfterConstructionDoesNotChangeWhatIsResolved() {
		Map<String, String> mutableSource = new HashMap<>(Map.of("ref-1", "top-secret"));
		ConfiguredWebhookSecretResolver resolver = new ConfiguredWebhookSecretResolver(
				new WebhookSecretProperties(mutableSource));

		mutableSource.put("ref-1", "tampered");
		mutableSource.put("ref-2", "injected-after-construction");

		assertThat(resolver.resolve("ref-1")).contains("top-secret");
		assertThat(resolver.resolve("ref-2")).isEmpty();
	}

	@Test
	void toStringContainsNoSecretValue() {
		ConfiguredWebhookSecretResolver resolver =
				new ConfiguredWebhookSecretResolver(new WebhookSecretProperties(Map.of("ref-1", "top-secret")));

		assertThat(resolver.toString()).doesNotContain("top-secret");
	}

	@Test
	void resolveNeverReturnsNull() {
		ConfiguredWebhookSecretResolver resolver =
				new ConfiguredWebhookSecretResolver(new WebhookSecretProperties(Map.of()));

		Optional<String> result = resolver.resolve("anything");

		assertThat(result).isNotNull();
	}
}
