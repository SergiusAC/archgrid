package dev.archgrid.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.archgrid.domain.metamodel.Aspect;
import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Layer;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipCategory;
import dev.archgrid.domain.metamodel.RelationshipType;
import dev.archgrid.domain.profile.Profile;
import dev.archgrid.domain.profile.ProfileCatalog;
import dev.archgrid.domain.shared.Slug;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The command pipeline's rules, on a miniature metamodel so a failure points at the rule rather
 * than at the data.
 */
class ModelChangeTest {

    private static final RelationshipType COMPOSITION =
            new RelationshipType("Composition", "Composition", 'c', RelationshipCategory.STRUCTURAL, 1);
    private static final RelationshipType FLOW =
            new RelationshipType("Flow", "Flow", 'f', RelationshipCategory.DYNAMIC, 0);
    private static final RelationshipType SERVING =
            new RelationshipType("Serving", "Serving", 'v', RelationshipCategory.DEPENDENCY, 0);
    private static final RelationshipType SPECIALIZATION =
            new RelationshipType("Specialization", "Specialization", 's', RelationshipCategory.OTHER, 0);

    private static final ElementType COMPONENT =
            new ElementType("ApplicationComponent", "Application Component", Layer.APPLICATION, Aspect.ACTIVE_STRUCTURE);
    private static final ElementType SYSTEM_SOFTWARE =
            new ElementType("SystemSoftware", "System Software", Layer.TECHNOLOGY, Aspect.ACTIVE_STRUCTURE);
    private static final ElementType DATA_OBJECT =
            new ElementType("DataObject", "Data Object", Layer.APPLICATION, Aspect.PASSIVE_STRUCTURE);

    private static final Metamodel METAMODEL = Metamodel.builder()
            .version("3.2")
            .relationshipType(COMPOSITION)
            .relationshipType(FLOW)
            .relationshipType(SERVING)
            .relationshipType(SPECIALIZATION)
            .elementType(COMPONENT)
            .elementType(SYSTEM_SOFTWARE)
            .elementType(DATA_OBJECT)
            .permit("ApplicationComponent", "ApplicationComponent", List.of(COMPOSITION, FLOW, SERVING, SPECIALIZATION))
            .permit("ApplicationComponent", "SystemSoftware", List.of(FLOW, SERVING))
            .permit("ApplicationComponent", "DataObject", List.of())
            .permit("SystemSoftware", "ApplicationComponent", List.of(FLOW, SERVING))
            .permit("SystemSoftware", "SystemSoftware", List.of(COMPOSITION, SPECIALIZATION))
            .permit("SystemSoftware", "DataObject", List.of())
            .permit("DataObject", "DataObject", List.of(SPECIALIZATION))
            .permit("DataObject", "ApplicationComponent", List.of())
            .permit("DataObject", "SystemSoftware", List.of())
            .build();

    private static final ProfileCatalog PROFILES = ProfileCatalog.builder()
            .profile(Profile.of("Microservice", "ApplicationComponent"))
            .build(METAMODEL);

    private static ModelChange.Result apply(ModelGraph base, ModelCommand... commands) {
        return ModelChange.apply(base, List.of(commands), METAMODEL, PROFILES);
    }

    @Test
    @DisplayName("the scenario from the product brief: Payment Service -> Kafka -> Notification Service")
    void builds_the_canonical_example() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of("payment-service", "ApplicationComponent", "Payment Service"),
                ModelCommand.CreateElement.of("kafka", "SystemSoftware", "Kafka"),
                ModelCommand.CreateElement.of("notification-service", "ApplicationComponent", "Notification Service"),
                ModelCommand.AddRelationship.of("payment-publishes", "Flow", "payment-service", "kafka"),
                ModelCommand.AddRelationship.of("kafka-serves-notification", "Serving", "kafka", "notification-service"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.graph().elements()).hasSize(3);
        assertThat(result.graph().relationships()).hasSize(2);
        assertThat(result.graph().element(Slug.of("kafka")).orElseThrow().name()).isEqualTo("Kafka");
    }

    @Test
    @DisplayName("a relationship the metamodel forbids is refused, and the message names both elements")
    void rejects_an_impermissible_relationship_with_an_explanation() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of("payment-service", "ApplicationComponent", "Payment Service"),
                ModelCommand.CreateElement.of("payments-db", "DataObject", "Payments"),
                ModelCommand.AddRelationship.of("bad", "Serving", "payment-service", "payments-db"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(Violation.Code.RELATIONSHIP_NOT_PERMITTED.code());
            assertThat(violation.subject()).isEqualTo("bad");
            assertThat(violation.detail())
                    .contains("Serving is not permitted from Application Component to Data Object")
                    .contains("No relationship type is permitted between these two")
                    .contains("Attempted between 'Payment Service' and 'Payments'");
        });
    }

    @Test
    @DisplayName("a profiled element is created as its base concept and keeps the profile for display")
    void creates_a_profiled_element() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of("payment-service", "Microservice", "Payment Service"),
                ModelCommand.CreateElement.of("kafka", "SystemSoftware", "Kafka"),
                ModelCommand.AddRelationship.of("publishes", "Flow", "payment-service", "kafka"));

        assertThat(result.isSuccess()).isTrue();
        Element element = result.graph().element(Slug.of("payment-service")).orElseThrow();
        assertThat(element.conceptType()).isEqualTo("ApplicationComponent");
        assertThat(element.profileName()).isEqualTo("Microservice");
        assertThat(element.displayType()).isEqualTo("Microservice");
    }

    @Test
    void rejects_an_unknown_type() {
        ModelChange.Result result =
                apply(ModelGraph.empty(), ModelCommand.CreateElement.of("thing", "Doohickey", "Thing"));

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(Violation.Code.UNKNOWN_TYPE.code());
            assertThat(violation.detail()).contains("neither an ArchiMate element type nor a profile");
        });
    }

    @Test
    void rejects_a_duplicate_key() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of("a", "ApplicationComponent", "First"),
                ModelCommand.CreateElement.of("a", "ApplicationComponent", "Second"));

        assertThat(result.violations())
                .extracting(Violation::code)
                .containsExactly(Violation.Code.DUPLICATE_KEY.code());
    }

    @Test
    void rejects_a_relationship_to_an_element_that_does_not_exist() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of("a", "ApplicationComponent", "A"),
                ModelCommand.AddRelationship.of("r", "Flow", "a", "ghost"));

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(Violation.Code.DANGLING_ENDPOINT.code());
            assertThat(violation.detail()).contains("'ghost'");
        });
    }

    @Test
    @DisplayName("every problem in a batch is reported, not just the first")
    void reports_all_violations_at_once() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of("a", "Doohickey", "A"),
                ModelCommand.CreateElement.of("b", "Whatsit", "B"),
                ModelCommand.CreateElement.of("c", "ApplicationComponent", "C"),
                ModelCommand.CreateElement.of("c", "ApplicationComponent", "C again"));

        assertThat(result.violations()).hasSize(3);
        assertThat(result.violations()).extracting(Violation::subject).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("containment that would make an element its own ancestor is refused")
    void rejects_containment_cycles() {
        ModelGraph base = ModelGraph.of(
                List.of(
                        Element.of(Slug.of("a"), "ApplicationComponent", "A"),
                        new Element(
                                Slug.of("b"),
                                "ApplicationComponent",
                                null,
                                "B",
                                null,
                                java.util.Map.of(),
                                Slug.of("a"))),
                List.of());

        ModelChange.Result result = apply(base, new ModelCommand.MoveElement(Slug.of("a"), Slug.of("b")));

        assertThat(result.violations())
                .extracting(Violation::code)
                .contains(Violation.Code.CONTAINMENT_CYCLE.code());
    }

    @Test
    @DisplayName("deleting an element takes its relationships with it")
    void deleting_an_element_removes_its_relationships() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of("a", "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of("b", "SystemSoftware", "B"),
                ModelCommand.AddRelationship.of("r", "Flow", "a", "b"));
        assertThat(built.isSuccess()).isTrue();

        ModelChange.Result result = apply(built.graph(), new ModelCommand.DeleteElement(Slug.of("b")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.graph().elements()).hasSize(1);
        assertThat(result.graph().relationships()).isEmpty();
    }

    @Test
    @DisplayName("deleting a container refuses rather than silently taking the subtree with it")
    void refuses_to_delete_an_element_that_still_contains_others() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of("system", "ApplicationComponent", "System"),
                new ModelCommand.CreateElement(
                        Slug.of("part"), "ApplicationComponent", "Part", null, Slug.of("system")));
        assertThat(built.isSuccess()).isTrue();

        ModelChange.Result result = apply(built.graph(), new ModelCommand.DeleteElement(Slug.of("system")));

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(Violation.Code.NOT_APPLICABLE.code());
            assertThat(violation.detail()).contains("still contains 1 element(s)").contains("part");
        });
    }

    @Test
    void renames_and_sets_properties() {
        ModelChange.Result built =
                apply(ModelGraph.empty(), ModelCommand.CreateElement.of("a", "ApplicationComponent", "A"));

        ModelChange.Result result = apply(
                built.graph(),
                new ModelCommand.RenameElement(Slug.of("a"), "Payment Service"),
                new ModelCommand.SetElementDocumentation(Slug.of("a"), "Handles card payments."),
                new ModelCommand.SetElementProperty(Slug.of("a"), "owner-team", "payments"));

        assertThat(result.isSuccess()).isTrue();
        Element element = result.graph().element(Slug.of("a")).orElseThrow();
        assertThat(element.name()).isEqualTo("Payment Service");
        assertThat(element.documentation()).isEqualTo("Handles card payments.");
        assertThat(element.properties()).containsEntry("owner-team", "payments");

        ModelChange.Result removed =
                apply(result.graph(), new ModelCommand.SetElementProperty(Slug.of("a"), "owner-team", null));
        assertThat(removed.graph().element(Slug.of("a")).orElseThrow().properties()).isEmpty();
    }

    @Test
    @DisplayName("a rejected batch leaves the original graph untouched")
    void commands_are_applied_to_a_copy() {
        ModelGraph base = ModelGraph.empty();

        ModelChange.Result result =
                apply(base, ModelCommand.CreateElement.of("a", "Doohickey", "A"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(base.elements()).isEmpty();
    }
}
