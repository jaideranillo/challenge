package com.cobre.challenge.domain.model.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TenantIdTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "CLIENT001",
        "A",
        "a1234567890123456789012345678901234567890123456789012345678901",
        "client_id-with-dashes_and_underscores"
    })
    void acceptsValidValues(String value) {
        assertThat(new TenantId(value).value()).isEqualTo(value);
    }

    @Test
    void accepts64CharacterValue() {
        String sixtyFour = "a".repeat(64);
        assertThat(new TenantId(sixtyFour).value()).isEqualTo(sixtyFour);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new TenantId(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        " ",
        "\t",
        "client id",
        "client';DROP TABLE",
        "client%20id",
        "\"client\"",
        "client\nid",
        "clïent"
    })
    void rejectsInvalidValues(String value) {
        assertThatThrownBy(() -> new TenantId(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects65CharacterValue() {
        String tooLong = "a".repeat(65);
        assertThatThrownBy(() -> new TenantId(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameValueIsEqualAndHashesTheSame() {
        TenantId first = new TenantId("CLIENT001");
        TenantId second = new TenantId("CLIENT001");

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
