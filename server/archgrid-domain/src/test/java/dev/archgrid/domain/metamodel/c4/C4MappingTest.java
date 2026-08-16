package dev.archgrid.domain.metamodel.c4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.archgrid.domain.metamodel.Aspect;
import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Layer;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipCategory;
import dev.archgrid.domain.metamodel.RelationshipType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The guards that stop a hand-authored C4 mapping from failing silently. */
class C4MappingTest {

    private static final ElementType COMPONENT =
            new ElementType("ApplicationComponent", "Application Component", Layer.APPLICATION, Aspect.ACTIVE_STRUCTURE);
    private static final ElementType SYSTEM_SOFTWARE =
            new ElementType("SystemSoftware", "System Software", Layer.TECHNOLOGY, Aspect.ACTIVE_STRUCTURE);
    private static final RelationshipType SERVING =
            new RelationshipType("Serving", "Serving", 'v', RelationshipCategory.DEPENDENCY, 0);

    private static final Metamodel METAMODEL = Metamodel.builder()
            .version("3.2")
            .elementType(COMPONENT)
            .elementType(SYSTEM_SOFTWARE)
            .relationshipType(SERVING)
            .build();

    private static final C4RelationshipIntent USES =
            new C4RelationshipIntent("uses", "A uses B", SERVING, true);

    private static C4Mapping.Builder minimal() {
        return C4Mapping.builder()
                .shape("ApplicationComponent", C4Shape.BOX)
                .level(new C4Level("context", "System Context", 0, Set.of(Layer.APPLICATION)))
                .intent(USES)
                .defaultIntent("uses");
    }

    @Test
    @DisplayName("an unmapped concept is omitted rather than missing")
    void unmapped_concepts_are_omitted() {
        C4Mapping mapping = minimal().build(METAMODEL);

        assertThat(mapping.shapeOf("ApplicationComponent")).isEqualTo(C4Shape.BOX);
        assertThat(mapping.shapeOf("SystemSoftware")).isEqualTo(C4Shape.OMITTED);
        assertThat(mapping.shapeOf("AnythingAtAll")).isEqualTo(C4Shape.OMITTED);
    }

    @Test
    @DisplayName("a mapping naming a concept the metamodel does not declare fails at load")
    void rejects_concepts_the_metamodel_does_not_know() {
        assertThatThrownBy(() -> minimal().shape("Microservice", C4Shape.BOX).build(METAMODEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Microservice")
                .hasMessageContaining("does not declare");
    }

    @Test
    void rejects_relationship_types_the_metamodel_does_not_know() {
        RelationshipType invented =
                new RelationshipType("Invokes", "Invokes", 'x', RelationshipCategory.DEPENDENCY, 0);

        assertThatThrownBy(() -> minimal()
                        .intent(new C4RelationshipIntent("calls", "A calls B", invented, false))
                        .build(METAMODEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invokes");
    }

    @Test
    @DisplayName("declaring 'omitted' is rejected: it is the default, and two sources of truth would drift")
    void rejects_explicit_omitted_declarations() {
        assertThatThrownBy(() -> minimal().shape("SystemSoftware", C4Shape.OMITTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omitted is the default");
    }

    @Test
    void rejects_two_levels_at_the_same_depth() {
        assertThatThrownBy(() -> minimal()
                        .level(new C4Level("container", "Container", 0, Set.of(Layer.APPLICATION)))
                        .build(METAMODEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same depth");
    }

    @Test
    void rejects_a_default_intent_that_is_not_declared() {
        assertThatThrownBy(() -> C4Mapping.builder()
                        .shape("ApplicationComponent", C4Shape.BOX)
                        .level(new C4Level("context", "System Context", 0, Set.of(Layer.APPLICATION)))
                        .intent(USES)
                        .defaultIntent("invents")
                        .build(METAMODEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invents");
    }

    @Test
    void rejects_a_level_admitting_no_layer() {
        assertThatThrownBy(() -> new C4Level("empty", "Empty", 0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("would always be empty");
    }

    @Test
    @DisplayName("the reverse flag is what keeps an imported model from facing backwards")
    void intent_applies_direction() {
        C4RelationshipIntent.Directed reversed = USES.apply("NotificationService", "Kafka");
        assertThat(reversed.source()).isEqualTo("Kafka");
        assertThat(reversed.target()).isEqualTo("NotificationService");

        C4RelationshipIntent straight =
                new C4RelationshipIntent("sends", "A sends to B", SERVING, false);
        C4RelationshipIntent.Directed kept = straight.apply("PaymentService", "Kafka");
        assertThat(kept.source()).isEqualTo("PaymentService");
        assertThat(kept.target()).isEqualTo("Kafka");
    }

    @Test
    @DisplayName("technology discriminators are matched case-insensitively, first match winning")
    void discriminator_matching() {
        C4ElementRule rule = new C4ElementRule(
                "container",
                COMPONENT,
                List.of(new C4ElementRule.Discriminator(SYSTEM_SOFTWARE, List.of("PostgreSQL", "kafka"))));

        assertThat(rule.resolve("PostgreSQL 16")).isEqualTo(SYSTEM_SOFTWARE);
        assertThat(rule.resolve("postgresql")).isEqualTo(SYSTEM_SOFTWARE);
        assertThat(rule.resolve("Apache KAFKA 4")).isEqualTo(SYSTEM_SOFTWARE);
        assertThat(rule.resolve("Spring Boot")).isEqualTo(COMPONENT);
        assertThat(rule.resolve("  ")).isEqualTo(COMPONENT);
        assertThat(rule.resolve(null)).isEqualTo(COMPONENT);
    }

    @Test
    void rejects_a_discriminator_that_can_never_fire() {
        assertThatThrownBy(() -> new C4ElementRule.Discriminator(SYSTEM_SOFTWARE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("would never fire");
    }
}
