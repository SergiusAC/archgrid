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
    @DisplayName("composition that would make an element its own ancestor is refused")
    void rejects_containment_cycles() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of("a", "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of("b", "ApplicationComponent", "B"),
                ModelCommand.AddRelationship.of("a-holds-b", "Composition", "a", "b"));
        assertThat(built.isSuccess()).isTrue();

        // The rule outlived the field it used to be checked on: containment is a Composition row
        // now (ADR-0011), and a part that owns its own whole is not an unusual model but an
        // impossible one.
        ModelChange.Result result =
                apply(built.graph(), ModelCommand.AddRelationship.of("b-holds-a", "Composition", "b", "a"));

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
    @DisplayName("deleting a container leaves what it contained in the repository")
    void deleting_a_container_keeps_its_contents() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of("system", "ApplicationComponent", "System"),
                ModelCommand.CreateElement.of("part", "ApplicationComponent", "Part"),
                ModelCommand.AddRelationship.of("system-holds-part", "Composition", "system", "part"));
        assertThat(built.isSuccess()).isTrue();

        ModelChange.Result result = apply(built.graph(), new ModelCommand.DeleteElement(Slug.of("system")));

        // Containment is a relationship, not ownership of a row (ADR-0011): the Composition cannot
        // survive the loss of an endpoint, and the part is an element in its own right.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.graph().elements()).extracting(element -> element.key().value()).containsExactly("part");
        assertThat(result.graph().relationships()).isEmpty();
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

    // ------------------------------------------------------------------ import tolerance (ADR-0015)

    private static ModelChange.ImportResult applyForImport(ModelCommand... commands) {
        return ModelChange.applyForImport(ModelGraph.empty(), List.of(commands), METAMODEL, PROFILES);
    }

    @Test
    @DisplayName("import keeps what the language permits and discards what it forbids, by name")
    void import_discards_the_impermissible_and_keeps_the_rest() {
        ModelChange.ImportResult result = applyForImport(
                ModelCommand.CreateElement.of("api", "ApplicationComponent", "API"),
                ModelCommand.CreateElement.of("db", "DataObject", "DB"),
                ModelCommand.CreateElement.of("runtime", "SystemSoftware", "Runtime"),
                // Permitted, stays.
                ModelCommand.AddRelationship.of("api-runtime", "Serving", "api", "runtime"),
                // The matrix forbids anything from ApplicationComponent to DataObject: discarded.
                ModelCommand.AddRelationship.of("api-db", "Flow", "api", "db"));

        assertThat(result.graph().elements()).hasSize(3);
        assertThat(result.graph().relationships())
                .extracting(relationship -> relationship.key().value())
                .containsExactly("api-runtime");
        assertThat(result.discarded()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(Violation.Code.RELATIONSHIP_NOT_PERMITTED.code());
            assertThat(violation.subject()).isEqualTo("api-db");
        });
    }

    @Test
    @DisplayName("a composition cycle in an imported file is broken, not imported and not fatal")
    void import_breaks_a_containment_cycle() {
        // The strict pipeline refuses this batch whole; an import has to keep both elements and
        // give up one of the two impossible edges. Which one is arbitrary — the cycle is the
        // impossible claim, not any particular edge of it.
        ModelChange.ImportResult result = applyForImport(
                ModelCommand.CreateElement.of("a", "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of("b", "ApplicationComponent", "B"),
                ModelCommand.AddRelationship.of("a-holds-b", "Composition", "a", "b"),
                ModelCommand.AddRelationship.of("b-holds-a", "Composition", "b", "a"));

        assertThat(result.graph().elements()).hasSize(2);
        assertThat(result.graph().relationships()).hasSize(1);
        assertThat(result.discarded())
                .extracting(Violation::code)
                .containsExactly(Violation.Code.CONTAINMENT_CYCLE.code());
    }

    @Test
    @DisplayName("per-command refusals become report entries: the duplicate and the dangling line")
    void import_reports_skipped_commands() {
        ModelChange.ImportResult result = applyForImport(
                ModelCommand.CreateElement.of("api", "ApplicationComponent", "API"),
                // Same key again: skipped, reported, the first survives untouched.
                ModelCommand.CreateElement.of("api", "SystemSoftware", "API again"),
                // An endpoint the file never defines: skipped, reported.
                ModelCommand.AddRelationship.of("api-ghost", "Serving", "api", "ghost"));

        assertThat(result.graph().elements()).hasSize(1);
        assertThat(result.graph().element(Slug.of("api")).orElseThrow().conceptType())
                .isEqualTo("ApplicationComponent");
        assertThat(result.graph().relationships()).isEmpty();
        assertThat(result.discarded())
                .extracting(Violation::code)
                .containsExactlyInAnyOrder(
                        Violation.Code.DUPLICATE_KEY.code(), Violation.Code.DANGLING_ENDPOINT.code());
    }

    @Test
    @DisplayName("a clean file imports with an empty report and matches the strict pipeline")
    void import_of_a_clean_file_discards_nothing() {
        ModelCommand[] commands = {
            ModelCommand.CreateElement.of("api", "ApplicationComponent", "API"),
            ModelCommand.CreateElement.of("runtime", "SystemSoftware", "Runtime"),
            ModelCommand.AddRelationship.of("api-runtime", "Serving", "api", "runtime"),
        };

        ModelChange.ImportResult tolerant = applyForImport(commands);
        ModelChange.Result strict = apply(ModelGraph.empty(), commands);

        assertThat(tolerant.discarded()).isEmpty();
        assertThat(strict.isSuccess()).isTrue();
        assertThat(tolerant.graph().elements()).hasSize(strict.graph().elements().size());
        assertThat(tolerant.graph().relationships()).hasSize(strict.graph().relationships().size());
    }

    @Test
    @DisplayName("what an import keeps is a graph the strict pipeline accepts")
    void what_import_keeps_is_valid() {
        ModelChange.ImportResult imported = applyForImport(
                ModelCommand.CreateElement.of("a", "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of("db", "DataObject", "DB"),
                ModelCommand.AddRelationship.of("bad", "Flow", "a", "db"),
                ModelCommand.AddRelationship.of("worse", "Composition", "db", "a"));

        // The guarantee ADR-0015 makes: after import, the repository keeps its central promise.
        // Checked with the strict pipeline itself rather than restated here.
        ModelChange.Result revalidated =
                ModelChange.apply(imported.graph(), List.of(), METAMODEL, PROFILES);
        assertThat(revalidated.isSuccess()).isTrue();
        assertThat(imported.discarded()).hasSize(2);
    }
}
