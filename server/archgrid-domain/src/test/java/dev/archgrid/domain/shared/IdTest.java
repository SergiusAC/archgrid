package dev.archgrid.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdTest {

    @Test
    @DisplayName("parses the canonical UUID form and prints it back unchanged")
    void round_trips_canonical_form() {
        Id id = Id.parse("8f3a1c2e-4b5d-4e6f-9a0b-1c2d3e4f5a6b");

        assertThat(id.value()).isEqualTo(UUID.fromString("8f3a1c2e-4b5d-4e6f-9a0b-1c2d3e4f5a6b"));
        assertThat(id.toString()).isEqualTo("8f3a1c2e-4b5d-4e6f-9a0b-1c2d3e4f5a6b");
        assertThat(Id.parse("  8f3a1c2e-4b5d-4e6f-9a0b-1c2d3e4f5a6b ")).isEqualTo(id);
    }

    @Test
    @DisplayName("refuses anything that is not a UUID, saying what was expected")
    void refuses_non_uuids() {
        // A slug — the old identity — is exactly what a stale client would still send.
        assertThatThrownBy(() -> Id.parse("payment-api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment-api")
                .hasMessageContaining("UUID");
        assertThatThrownBy(() -> Id.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("generated ids are distinct; named ids are stable")
    void generated_and_named() {
        assertThat(Id.generate()).isNotEqualTo(Id.generate());
        assertThat(Id.named("kafka")).isEqualTo(Id.named("kafka"));
        assertThat(Id.named("kafka")).isNotEqualTo(Id.named("Kafka"));
    }

    @Test
    @DisplayName("orders by value, so a sorted listing is deterministic")
    void comparable() {
        Id low = Id.parse("00000000-0000-0000-0000-000000000001");
        Id high = Id.parse("00000000-0000-0000-0000-000000000002");
        assertThat(low.compareTo(high)).isNegative();
        assertThat(high.compareTo(low)).isPositive();
        assertThat(low.compareTo(Id.parse("00000000-0000-0000-0000-000000000001"))).isZero();
    }
}
