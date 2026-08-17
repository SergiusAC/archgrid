package dev.archgrid.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.archgrid.domain.metamodel.Aspect;
import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Layer;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipCategory;
import dev.archgrid.domain.metamodel.RelationshipType;
import dev.archgrid.domain.shared.Slug;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rule that makes a context diagram truthful rather than merely tidy. */
class RelationshipDerivationTest {

    private static final RelationshipType COMPOSITION =
            new RelationshipType("Composition", "Composition", 'c', RelationshipCategory.STRUCTURAL, 1);
    private static final RelationshipType AGGREGATION =
            new RelationshipType("Aggregation", "Aggregation", 'g', RelationshipCategory.STRUCTURAL, 2);
    private static final RelationshipType ASSIGNMENT =
            new RelationshipType("Assignment", "Assignment", 'i', RelationshipCategory.STRUCTURAL, 3);
    private static final RelationshipType SERVING =
            new RelationshipType("Serving", "Serving", 'v', RelationshipCategory.DEPENDENCY, 0);
    private static final RelationshipType FLOW =
            new RelationshipType("Flow", "Flow", 'f', RelationshipCategory.DYNAMIC, 0);
    private static final RelationshipType SPECIALIZATION =
            new RelationshipType("Specialization", "Specialization", 's', RelationshipCategory.OTHER, 0);

    private static final ElementType COMPONENT =
            new ElementType("ApplicationComponent", "Application Component", Layer.APPLICATION, Aspect.ACTIVE_STRUCTURE);

    private static final Metamodel METAMODEL = Metamodel.builder()
            .version("3.2")
            .relationshipType(COMPOSITION)
            .relationshipType(AGGREGATION)
            .relationshipType(ASSIGNMENT)
            .relationshipType(SERVING)
            .relationshipType(FLOW)
            .relationshipType(SPECIALIZATION)
            .elementType(COMPONENT)
            .permit("ApplicationComponent", "ApplicationComponent", List.of(COMPOSITION, AGGREGATION, ASSIGNMENT, SERVING, FLOW))
            .build();

    private static Element element(String key, String parent) {
        return new Element(
                Slug.of(key), "ApplicationComponent", null, key, null, Map.of(), parent == null ? null : Slug.of(parent));
    }

    private static Relationship relationship(String key, String type, String source, String target) {
        return Relationship.of(Slug.of(key), type, Slug.of(source), Slug.of(target));
    }

    private static Set<Slug> visible(String... keys) {
        return java.util.Arrays.stream(keys).map(Slug::of).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("what a contained element serves, its container serves at the level above")
    void containment_lifts_a_dependency_to_the_container() {
        ModelGraph graph = ModelGraph.of(
                List.of(element("payment-service", null), element("payment-api", "payment-service"), element("notification-service", null)),
                List.of(relationship("api-serves", "Serving", "payment-api", "notification-service")));

        List<DerivedRelationship> derived =
                RelationshipDerivation.derive(graph, visible("payment-service", "notification-service"), METAMODEL);

        assertThat(derived).singleElement().satisfies(relationship -> {
            assertThat(relationship.source()).isEqualTo(Slug.of("payment-service"));
            assertThat(relationship.target()).isEqualTo(Slug.of("notification-service"));
            assertThat(relationship.type()).isEqualTo(SERVING);
            assertThat(relationship.via()).containsExactly(Slug.of("payment-api"));
        });
    }

    @Test
    @DisplayName("a chain of structural relationships derives the weakest of them")
    void structural_chain_derives_the_weakest_link() {
        ModelGraph graph = ModelGraph.of(
                List.of(element("a", null), element("b", null), element("c", null)),
                List.of(
                        relationship("a-b", "Composition", "a", "b"),
                        relationship("b-c", "Assignment", "b", "c")));

        List<DerivedRelationship> derived = RelationshipDerivation.derive(graph, visible("a", "c"), METAMODEL);

        assertThat(derived).singleElement().satisfies(relationship -> {
            // Composition is stronger than Assignment, so the chain is only as strong as
            // Assignment.
            assertThat(relationship.type()).isEqualTo(ASSIGNMENT);
            assertThat(relationship.via()).containsExactly(Slug.of("b"));
        });
    }

    @Test
    @DisplayName("nothing derives past a dependency")
    void a_chain_does_not_continue_through_a_dependency() {
        ModelGraph graph = ModelGraph.of(
                List.of(element("a", null), element("b", null), element("c", null), element("d", null)),
                List.of(
                        relationship("a-b", "Composition", "a", "b"),
                        relationship("b-c", "Serving", "b", "c"),
                        relationship("c-d", "Serving", "c", "d")));

        // a→d would need to cross two dependencies; the model makes no such claim.
        assertThat(RelationshipDerivation.derive(graph, visible("a", "d"), METAMODEL)).isEmpty();
        // a→c is one structural plus one dependency, which does derive.
        assertThat(RelationshipDerivation.derive(graph, visible("a", "c"), METAMODEL))
                .singleElement()
                .satisfies(relationship -> assertThat(relationship.type()).isEqualTo(SERVING));
    }

    @Test
    @DisplayName("an explicit relationship wins; nothing duplicates it")
    void explicit_relationships_are_not_duplicated() {
        ModelGraph graph = ModelGraph.of(
                List.of(element("a", null), element("hidden", "a"), element("c", null)),
                List.of(
                        relationship("hidden-c", "Serving", "hidden", "c"),
                        relationship("a-c", "Serving", "a", "c")));

        assertThat(RelationshipDerivation.derive(graph, visible("a", "c"), METAMODEL)).isEmpty();
    }

    @Test
    @DisplayName("a path with nothing hidden in the middle derives nothing — it is already drawn")
    void adjacent_visible_elements_derive_nothing() {
        ModelGraph graph = ModelGraph.of(
                List.of(element("a", null), element("b", null)),
                List.of(relationship("a-b", "Serving", "a", "b")));

        assertThat(RelationshipDerivation.derive(graph, visible("a", "b"), METAMODEL)).isEmpty();
    }

    @Test
    @DisplayName("specialization is not part of the derivation rules")
    void specialization_does_not_derive() {
        Metamodel withSpecialization = Metamodel.builder()
                .version("3.2")
                .relationshipType(COMPOSITION)
                .relationshipType(SPECIALIZATION)
                .elementType(COMPONENT)
                .permit("ApplicationComponent", "ApplicationComponent", List.of(COMPOSITION, SPECIALIZATION))
                .build();
        ModelGraph graph = ModelGraph.of(
                List.of(element("a", null), element("b", null), element("c", null)),
                List.of(
                        relationship("a-b", "Composition", "a", "b"),
                        relationship("b-c", "Specialization", "b", "c")));

        assertThat(RelationshipDerivation.derive(graph, visible("a", "c"), withSpecialization))
                .isEmpty();
    }

    @Test
    @DisplayName("a cycle terminates instead of deriving forever")
    void cycles_terminate() {
        ModelGraph graph = ModelGraph.of(
                List.of(element("a", null), element("b", null), element("c", null)),
                List.of(
                        relationship("a-b", "Composition", "a", "b"),
                        relationship("b-c", "Composition", "b", "c"),
                        relationship("c-b", "Composition", "c", "b")));

        // Completes rather than hanging, which is the whole assertion.
        assertThat(RelationshipDerivation.derive(graph, visible("a", "c"), METAMODEL))
                .allSatisfy(relationship -> assertThat(relationship.via()).isNotEmpty());
    }

    @Test
    @DisplayName("a derived line can say why it is there")
    void derived_relationships_carry_their_path() {
        ModelGraph graph = ModelGraph.of(
                List.of(element("system", null), element("part", "system"), element("other", null)),
                List.of(relationship("part-flows", "Flow", "part", "other")));

        DerivedRelationship derived =
                RelationshipDerivation.derive(graph, visible("system", "other"), METAMODEL).get(0);

        assertThat(derived.type()).isEqualTo(FLOW);
        assertThat(derived.key()).isEqualTo("derived:system:flow:other");
        assertThat(derived.explain("System", "Other", List.of("Part")))
                .isEqualTo("Flow from System to Other, derived through Part.");
    }
}
