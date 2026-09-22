package com.cobre.challenge.adapter.out.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Plain JUnit, no Spring context: validates {@code ApiDataSourceProperties} constraints directly. */
class ApiDataSourcePropertiesTest {

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
    void rejectsBlankUsername() {
        ApiDataSourceProperties properties = new ApiDataSourceProperties("", "secret");

        Set<ConstraintViolation<ApiDataSourceProperties>> violations = validator.validate(properties);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("username"));
    }

    @Test
    void rejectsBlankPassword() {
        ApiDataSourceProperties properties = new ApiDataSourceProperties("challenge_api", "");

        Set<ConstraintViolation<ApiDataSourceProperties>> violations = validator.validate(properties);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    void acceptsValidValues() {
        ApiDataSourceProperties properties = new ApiDataSourceProperties("challenge_api", "secret");

        Set<ConstraintViolation<ApiDataSourceProperties>> violations = validator.validate(properties);

        assertThat(violations).isEmpty();
    }
}
