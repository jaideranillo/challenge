package com.cobre.challenge.adapter.out.secrets.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code challenge.webhook.secrets}: reference -> HMAC secret material (ADR-004 §3).
 *
 * <p>No secrets manager exists in this project (see {@code docs/concerns.md}); this is the
 * config-backed stand-in behind {@code WebhookSecretPort}, deliberately scoped so a real
 * secrets-manager adapter is a one-class swap. {@code application.yaml} carries no entry for
 * this key: the local profile and each environment supply their own values via an environment
 * variable or an untracked file.
 *
 * <p>Deliberately a plain class, not a record: a record's generated {@code toString()} would
 * print every secret if this bean were ever logged or dumped by an actuator endpoint.
 * {@code toString()} is overridden to a constant for the same reason.
 */
@ConfigurationProperties("challenge.webhook")
public class WebhookSecretProperties {

	private final Map<String, String> secrets;

	public WebhookSecretProperties(Map<String, String> secrets) {
		this.secrets = new HashMap<>(Objects.requireNonNullElseGet(secrets, Map::of));
	}

	public Map<String, String> secrets() {
		return Map.copyOf(secrets);
	}

	@Override
	public String toString() {
		return "WebhookSecretProperties[secrets=<redacted>]";
	}
}
