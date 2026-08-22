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
import dev.archgrid.domain.shared.Id;
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

    /** A readable identity for a scenario: the same name gives the same id, which is all a test needs. */
    private static Id id(String name) {
        return Id.named(name);
    }

    @Test
    @DisplayName("the scenario from the product brief: Payment Service -> Kafka -> Notification Service")
    void builds_the_canonical_example() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("payment-service"), "ApplicationComponent", "Payment Service"),
                ModelCommand.CreateElement.of(id("kafka"), "SystemSoftware", "Kafka"),
                ModelCommand.CreateElement.of(id("notification-service"), "ApplicationComponent", "Notification Service"),
                ModelCommand.AddRelationship.of(id("payment-publishes"), "Flow", id("payment-service"), id("kafka")),
                ModelCommand.AddRelationship.of(id("kafka-serves-notification"), "Serving", id("kafka"), id("notification-service")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.graph().elements()).hasSize(3);
        assertThat(result.graph().relationships()).hasSize(2);
        assertThat(result.graph().element(id("kafka")).orElseThrow().name()).isEqualTo("Kafka");
    }

    @Test
    @DisplayName("a relationship the metamodel forbids is refused, and the message names both elements")
    void rejects_an_impermissible_relationship_with_an_explanation() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("payment-service"), "ApplicationComponent", "Payment Service"),
                ModelCommand.CreateElement.of(id("payments-db"), "DataObject", "Payments"),
                ModelCommand.AddRelationship.of(id("bad"), "Serving", id("payment-service"), id("payments-db")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(Violation.Code.RELATIONSHIP_NOT_PERMITTED.code());
            assertThat(violation.subject()).isEqualTo(id("bad").toString());
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
                ModelCommand.CreateElement.of(id("payment-service"), "Microservice", "Payment Service"),
                ModelCommand.CreateElement.of(id("kafka"), "SystemSoftware", "Kafka"),
                ModelCommand.AddRelationship.of(id("publishes"), "Flow", id("payment-service"), id("kafka")));

        assertThat(result.isSuccess()).isTrue();
        Element element = result.graph().element(id("payment-service")).orElseThrow();
        assertThat(element.conceptType()).isEqualTo("ApplicationComponent");
        assertThat(element.profileName()).isEqualTo("Microservice");
        assertThat(element.displayType()).isEqualTo("Microservice");
    }

    @Test
    void rejects_an_unknown_type() {
        ModelChange.Result result =
                apply(ModelGraph.empty(), ModelCommand.CreateElement.of(id("thing"), "Doohickey", "Thing"));

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(Violation.Code.UNKNOWN_TYPE.code());
            assertThat(violation.detail()).contains("neither an ArchiMate element type nor a profile");
        });
    }

    @Test
    void rejects_a_duplicate_key() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "First"),
                ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "Second"));

        assertThat(result.violations())
                .extracting(Violation::code)
                .containsExactly(Violation.Code.DUPLICATE_KEY.code());
    }

    @Test
    void rejects_a_relationship_to_an_element_that_does_not_exist() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "A"),
                ModelCommand.AddRelationship.of(id("r"), "Flow", id("a"), id("ghost")));

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(Violation.Code.DANGLING_ENDPOINT.code());
            assertThat(violation.detail()).contains("'" + id("ghost") + "'");
        });
    }

    @Test
    @DisplayName("every problem in a batch is reported, not just the first")
    void reports_all_violations_at_once() {
        ModelChange.Result result = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("a"), "Doohickey", "A"),
                ModelCommand.CreateElement.of(id("b"), "Whatsit", "B"),
                ModelCommand.CreateElement.of(id("c"), "ApplicationComponent", "C"),
                ModelCommand.CreateElement.of(id("c"), "ApplicationComponent", "C again"));

        assertThat(result.violations()).hasSize(3);
        assertThat(result.violations()).extracting(Violation::subject).containsExactly(id("a").toString(), id("b").toString(), id("c").toString());
    }

    @Test
    @DisplayName("composition that would make an element its own ancestor is refused")
    void rejects_containment_cycles() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of(id("b"), "ApplicationComponent", "B"),
                ModelCommand.AddRelationship.of(id("a-holds-b"), "Composition", id("a"), id("b")));
        assertThat(built.isSuccess()).isTrue();

        // The rule outlived the field it used to be checked on: containment is a Composition row
        // now (ADR-0011), and a part that owns its own whole is not an unusual model but an
        // impossible one.
        ModelChange.Result result =
                apply(built.graph(), ModelCommand.AddRelationship.of(id("b-holds-a"), "Composition", id("b"), id("a")));

        assertThat(result.violations())
                .extracting(Violation::code)
                .contains(Violation.Code.CONTAINMENT_CYCLE.code());
    }

    @Test
    @DisplayName("deleting an element takes its relationships with it")
    void deleting_an_element_removes_its_relationships() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of(id("b"), "SystemSoftware", "B"),
                ModelCommand.AddRelationship.of(id("r"), "Flow", id("a"), id("b")));
        assertThat(built.isSuccess()).isTrue();

        ModelChange.Result result = apply(built.graph(), new ModelCommand.DeleteElement(id("b")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.graph().elements()).hasSize(1);
        assertThat(result.graph().relationships()).isEmpty();
    }

    @Test
    @DisplayName("deleting a container leaves what it contained in the repository")
    void deleting_a_container_keeps_its_contents() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("system"), "ApplicationComponent", "System"),
                ModelCommand.CreateElement.of(id("part"), "ApplicationComponent", "Part"),
                ModelCommand.AddRelationship.of(id("system-holds-part"), "Composition", id("system"), id("part")));
        assertThat(built.isSuccess()).isTrue();

        ModelChange.Result result = apply(built.graph(), new ModelCommand.DeleteElement(id("system")));

        // Containment is a relationship, not ownership of a row (ADR-0011): the Composition cannot
        // survive the loss of an endpoint, and the part is an element in its own right.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.graph().elements()).extracting(element -> element.key()).containsExactly(id("part"));
        assertThat(result.graph().relationships()).isEmpty();
    }

    @Test
    void renames_and_sets_properties() {
        ModelChange.Result built =
                apply(ModelGraph.empty(), ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "A"));

        ModelChange.Result result = apply(
                built.graph(),
                new ModelCommand.RenameElement(id("a"), "Payment Service"),
                new ModelCommand.SetElementDocumentation(id("a"), "Handles card payments."),
                new ModelCommand.SetElementProperty(id("a"), "owner-team", "payments"));

        assertThat(result.isSuccess()).isTrue();
        Element element = result.graph().element(id("a")).orElseThrow();
        assertThat(element.name()).isEqualTo("Payment Service");
        assertThat(element.documentation()).isEqualTo("Handles card payments.");
        assertThat(element.properties()).containsEntry("owner-team", "payments");

        ModelChange.Result removed =
                apply(result.graph(), new ModelCommand.SetElementProperty(id("a"), "owner-team", null));
        assertThat(removed.graph().element(id("a")).orElseThrow().properties()).isEmpty();
    }

    @Test
    @DisplayName("a rejected batch leaves the original graph untouched")
    void commands_are_applied_to_a_copy() {
        ModelGraph base = ModelGraph.empty();

        ModelChange.Result result =
                apply(base, ModelCommand.CreateElement.of(id("a"), "Doohickey", "A"));

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
                ModelCommand.CreateElement.of(id("api"), "ApplicationComponent", "API"),
                ModelCommand.CreateElement.of(id("db"), "DataObject", "DB"),
                ModelCommand.CreateElement.of(id("runtime"), "SystemSoftware", "Runtime"),
                // Permitted, stays.
                ModelCommand.AddRelationship.of(id("api-runtime"), "Serving", id("api"), id("runtime")),
                // The matrix forbids anything from ApplicationComponent to DataObject: discarded.
                ModelCommand.AddRelationship.of(id("api-db"), "Flow", id("api"), id("db")));

        assertThat(result.graph().elements()).hasSize(3);
        assertThat(result.graph().relationships())
                .extracting(relationship -> relationship.key())
                .containsExactly(id("api-runtime"));
        assertThat(result.discarded()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(Violation.Code.RELATIONSHIP_NOT_PERMITTED.code());
            assertThat(violation.subject()).isEqualTo(id("api-db").toString());
        });
    }

    @Test
    @DisplayName("a composition cycle in an imported file is broken, not imported and not fatal")
    void import_breaks_a_containment_cycle() {
        // The strict pipeline refuses this batch whole; an import has to keep both elements and
        // give up one of the two impossible edges. Which one is arbitrary — the cycle is the
        // impossible claim, not any particular edge of it.
        ModelChange.ImportResult result = applyForImport(
                ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of(id("b"), "ApplicationComponent", "B"),
                ModelCommand.AddRelationship.of(id("a-holds-b"), "Composition", id("a"), id("b")),
                ModelCommand.AddRelationship.of(id("b-holds-a"), "Composition", id("b"), id("a")));

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
                ModelCommand.CreateElement.of(id("api"), "ApplicationComponent", "API"),
                // Same key again: skipped, reported, the first survives untouched.
                ModelCommand.CreateElement.of(id("api"), "SystemSoftware", "API again"),
                // An endpoint the file never defines: skipped, reported.
                ModelCommand.AddRelationship.of(id("api-ghost"), "Serving", id("api"), id("ghost")));

        assertThat(result.graph().elements()).hasSize(1);
        assertThat(result.graph().element(id("api")).orElseThrow().conceptType())
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
            ModelCommand.CreateElement.of(id("api"), "ApplicationComponent", "API"),
            ModelCommand.CreateElement.of(id("runtime"), "SystemSoftware", "Runtime"),
            ModelCommand.AddRelationship.of(id("api-runtime"), "Serving", id("api"), id("runtime")),
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
                ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of(id("db"), "DataObject", "DB"),
                ModelCommand.AddRelationship.of(id("bad"), "Flow", id("a"), id("db")),
                ModelCommand.AddRelationship.of(id("worse"), "Composition", id("db"), id("a")));

        // The guarantee ADR-0015 makes: after import, the repository keeps its central promise.
        // Checked with the strict pipeline itself rather than restated here.
        ModelChange.Result revalidated =
                ModelChange.apply(imported.graph(), List.of(), METAMODEL, PROFILES);
        assertThat(revalidated.isSuccess()).isTrue();
        assertThat(imported.discarded()).hasSize(2);
    }

    // ------------------------------------------------------------------ names and triples (docs/09)

    @Test
    @DisplayName("an element name is held by one element, compared case-insensitively and trimmed")
    void refuses_a_duplicate_element_name() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("kafka"), "SystemSoftware", "Kafka"));

        ModelChange.Result result = apply(
                built.graph(), ModelCommand.CreateElement.of(id("kafka-2"), "ApplicationComponent", "  KAFKA "));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo("duplicate-name");
            // The newcomer is the culprit, and the holder is named so the user can choose.
            assertThat(violation.subject()).isEqualTo(id("kafka-2").toString());
            assertThat(violation.detail()).contains(id("kafka").toString()).contains("SystemSoftware");
        });

        ModelChange.Result renamed = apply(
                built.graph(),
                ModelCommand.CreateElement.of(id("other"), "ApplicationComponent", "Other"),
                new ModelCommand.RenameElement(id("other"), "kafka"));
        assertThat(renamed.isSuccess()).isFalse();
        assertThat(renamed.violations()).extracting(Violation::code).containsExactly("duplicate-name");
    }

    @Test
    @DisplayName("two names may swap in one batch: the rule holds for the result, not for every step")
    void names_may_swap_within_a_batch() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of(id("b"), "ApplicationComponent", "B"));

        ModelChange.Result swapped = apply(
                built.graph(),
                new ModelCommand.RenameElement(id("a"), "B"),
                new ModelCommand.RenameElement(id("b"), "A"));

        assertThat(swapped.isSuccess()).isTrue();
        assertThat(swapped.graph().element(id("a")).orElseThrow().name()).isEqualTo("B");
    }

    @Test
    @DisplayName("a view name is held by one view")
    void refuses_a_duplicate_view_name() {
        // The miniature metamodel has no viewpoints, so the views are given rather than created.
        ModelGraph base = ModelGraph.of(
                List.of(),
                List.of(),
                List.of(View.of(id("v1"), "Context", "layered"), View.of(id("v2"), "Modules", "layered")));

        ModelChange.Result result = apply(base, new ModelCommand.RenameView(id("v2"), "context"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo("duplicate-name");
            assertThat(violation.subject()).isEqualTo(id("v2").toString());
        });
    }

    @Test
    @DisplayName("one relationship of a type between two elements; the other direction is another statement")
    void refuses_a_duplicate_relationship() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of(id("b"), "SystemSoftware", "B"),
                ModelCommand.AddRelationship.of(id("first"), "Flow", id("a"), id("b")));

        ModelChange.Result duplicate = apply(
                built.graph(), new ModelCommand.AddRelationship(id("second"), "Flow", id("a"), id("b"), "invoices"));
        assertThat(duplicate.isSuccess()).isFalse();
        assertThat(duplicate.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo("duplicate-relationship");
            assertThat(violation.subject()).isEqualTo(id("second").toString());
            assertThat(violation.detail()).contains(id("first").toString()).contains("'A'").contains("'B'");
        });

        ModelChange.Result different = apply(
                built.graph(),
                ModelCommand.AddRelationship.of(id("back"), "Flow", id("b"), id("a")),
                ModelCommand.AddRelationship.of(id("serves"), "Serving", id("a"), id("b")));
        assertThat(different.isSuccess()).isTrue();
        assertThat(different.graph().relationships()).hasSize(3);
    }

    @Test
    @DisplayName("a relationship can be named later, and unnamed with a null")
    void renames_a_relationship() {
        ModelChange.Result built = apply(
                ModelGraph.empty(),
                ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "A"),
                ModelCommand.CreateElement.of(id("b"), "SystemSoftware", "B"),
                ModelCommand.AddRelationship.of(id("r"), "Flow", id("a"), id("b")));

        ModelChange.Result named = apply(built.graph(), new ModelCommand.RenameRelationship(id("r"), "  events "));
        assertThat(named.isSuccess()).isTrue();
        assertThat(named.graph().relationship(id("r")).orElseThrow().name()).isEqualTo("events");

        ModelChange.Result unnamed = apply(named.graph(), new ModelCommand.RenameRelationship(id("r"), null));
        assertThat(unnamed.graph().relationship(id("r")).orElseThrow().name()).isNull();

        ModelChange.Result missing = apply(built.graph(), new ModelCommand.RenameRelationship(id("ghost"), "x"));
        assertThat(missing.violations()).extracting(Violation::code).containsExactly("unknown-subject");
    }

    @Test
    @DisplayName("import cuts the later holder of a name, and the later of two identical relationships")
    void import_cuts_duplicates_rather_than_refusing_the_file() {
        // The view is given: the miniature metamodel has no viewpoints to create one with.
        ModelGraph base = ModelGraph.of(List.of(), List.of(), List.of(View.of(id("v"), "Context", "layered")));
        ModelChange.ImportResult result = ModelChange.applyForImport(
                base,
                List.of(
                        ModelCommand.CreateElement.of(id("a"), "ApplicationComponent", "Ledger"),
                        ModelCommand.CreateElement.of(id("b"), "SystemSoftware", "Runtime"),
                        ModelCommand.CreateElement.of(id("a2"), "ApplicationComponent", "ledger"),
                        ModelCommand.AddRelationship.of(id("r1"), "Serving", id("a"), id("b")),
                        ModelCommand.AddRelationship.of(id("r2"), "Serving", id("a"), id("b")),
                        ModelCommand.AddRelationship.of(id("r3"), "Serving", id("a2"), id("b")),
                        ModelCommand.AddToView.of(id("v"), id("a2"))),
                METAMODEL,
                PROFILES);

        // The first "Ledger" stays; the second goes, with its relationship and its place on the view.
        assertThat(result.graph().elements()).extracting(Element::key).containsExactly(id("a"), id("b"));
        assertThat(result.graph().relationships()).extracting(Relationship::key).containsExactly(id("r1"));
        assertThat(result.graph().view(id("v")).orElseThrow().elements()).isEmpty();
        assertThat(result.discarded()).extracting(Violation::subject)
                .containsExactlyInAnyOrder(id("a2").toString(), id("r2").toString());
    }
}
