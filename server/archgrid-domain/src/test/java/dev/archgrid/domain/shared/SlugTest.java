package dev.archgrid.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SlugTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "payment-service", "kafka", "s3", "notification-service-v2", "a1-b2-c3"})
    void accepts_lowercase_hyphenated_identifiers(String value) {
        assertThat(Slug.of(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @DisplayName("rejects anything that would need escaping in a URL, file name or DSL identifier")
    @ValueSource(
            strings = {
                "",
                "-leading",
                "trailing-",
                "double--hyphen",
                "Upper",
                "with space",
                "with_underscore",
                "with.dot",
                "with/slash",
                "с-кириллицей",
            })
    void rejects_invalid_identifiers(String value) {
        assertThatThrownBy(() -> Slug.of(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> Slug.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_values_longer_than_the_documented_maximum() {
        String tooLong = "a".repeat(Slug.MAX_LENGTH + 1);
        assertThatThrownBy(() -> Slug.of(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(Slug.MAX_LENGTH));
    }

    @Test
    void accepts_a_value_of_exactly_the_maximum_length() {
        String atLimit = "a".repeat(Slug.MAX_LENGTH);
        assertThat(Slug.of(atLimit).value()).hasSize(Slug.MAX_LENGTH);
    }

    @Test
    void orders_lexicographically() {
        assertThat(Slug.of("alpha")).isLessThan(Slug.of("beta"));
    }
}
