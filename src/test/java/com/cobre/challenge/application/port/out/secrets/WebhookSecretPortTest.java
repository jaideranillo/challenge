package com.cobre.challenge.application.port.out.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WebhookSecretPortTest {

	@Test
	void isAnInterface() {
		assertThat(WebhookSecretPort.class.isInterface()).isTrue();
	}

	@Test
	void hasExactlyOneMethod() {
		assertThat(WebhookSecretPort.class.getDeclaredMethods()).hasSize(1);
	}

	@Test
	void resolveTakesAStringAndReturnsOptional() throws NoSuchMethodException {
		Method method = WebhookSecretPort.class.getDeclaredMethod("resolve", String.class);

		assertThat(method.getReturnType()).isEqualTo(Optional.class);
	}

	@Test
	void unknownReferenceViaAnInMemoryImplementationReturnsEmpty() {
		WebhookSecretPort port = secretRef -> Optional.empty();

		assertThat(port.resolve("unknown")).isEmpty();
	}
}
