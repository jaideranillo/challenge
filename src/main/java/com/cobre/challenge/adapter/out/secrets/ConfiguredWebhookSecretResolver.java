package com.cobre.challenge.adapter.out.secrets;

import com.cobre.challenge.adapter.out.secrets.config.WebhookSecretProperties;
import com.cobre.challenge.application.port.out.secrets.WebhookSecretPort;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config-backed {@link WebhookSecretPort}: looks up {@code secretRef} in the map bound from
 * {@code challenge.webhook.secrets} (ADR-004 §3). No caching layer, no refresh, no fallback to a
 * default secret.
 */
@Component
@EnableConfigurationProperties(WebhookSecretProperties.class)
public class ConfiguredWebhookSecretResolver implements WebhookSecretPort {

	private final Map<String, String> secrets;

	public ConfiguredWebhookSecretResolver(WebhookSecretProperties properties) {
		this.secrets = Map.copyOf(properties.secrets());
	}

	@Override
	public Optional<String> resolve(String secretRef) {
		if (secretRef == null || secretRef.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(secrets.get(secretRef));
	}

	@Override
	public String toString() {
		return "ConfiguredWebhookSecretResolver[secrets=<redacted>]";
	}
}
