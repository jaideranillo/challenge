package com.cobre.challenge.adapter.in.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties.Bulkhead;
import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties.CircuitBreaker;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Plain JUnit, no Spring context: validates {@code WorkerProperties} constraints directly. */
class WorkerPropertiesTest {

	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void setUp() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void tearDown() {
		factory.close();
	}

	@Test
	void rejectsNonPositiveBatchSize() {
		WorkerProperties properties = validProperties(0);

		Set<ConstraintViolation<WorkerProperties>> violations = validator.validate(properties);

		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("batchSize"));
	}

	@Test
	void rejectsNullCircuitBreaker() {
		WorkerProperties properties = new WorkerProperties(
				true,
				Duration.ofSeconds(20),
				10,
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				new Bulkhead(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(20)),
				null,
				Duration.ofHours(1),
				1024);

		Set<ConstraintViolation<WorkerProperties>> violations = validator.validate(properties);

		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("circuitBreaker"));
	}

	private static WorkerProperties validProperties(int batchSize) {
		return new WorkerProperties(
				true,
				Duration.ofSeconds(20),
				batchSize,
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				new Bulkhead(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(20)),
				new CircuitBreaker(10, Duration.ofSeconds(30), Duration.ofHours(1)),
				Duration.ofHours(1),
				1024);
	}
}
