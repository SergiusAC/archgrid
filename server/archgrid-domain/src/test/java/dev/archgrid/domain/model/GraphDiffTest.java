package dev.archgrid.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.archgrid.domain.shared.Id;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Two states of one repository, compared. The whole of what a history UI shows about a commit. */
class GraphDiffTest {

    private static final Id PAYMENTS = Id.named("payments");
    private static final Id KAFKA = Id.named("kafka");
    private static final Id NOTIFICATIONS = Id.named("notifications");
    private static final Id PUBLISHES = Id.named("publishes");
    private static final Id CONTEXT = Id.named("context");

    private static Element element(Id key, String name) {
        return new Element(key, "ApplicationComponent", null, name, null, Map.of());
    }

    private static GraphDiff.Change only(GraphDiff diff) {
        assertThat(diff.changes()).hasSize(1);
        return diff.changes().getFirst();
    }

    @Test
    @DisplayName("identical states differ in nothing")
    void an_unchanged_graph_produces_an_empty_diff() {
        ModelGraph graph = ModelGraph.of(List.of(element(PAYMENTS, "Payment Service")), List.of());

        GraphDiff diff = GraphDiff.between(graph, graph.copy());

        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.summary()).isEqualTo(new GraphDiff.Summary(0, 0, 0));
    }

    @Test
    @DisplayName("an element that appears is one addition, and carries no field changes")
    void a_new_element_is_added() {
        ModelGraph before = ModelGraph.of(List.of(), List.of());
        ModelGraph after = ModelGraph.of(List.of(element(PAYMENTS, "Payment Service")), List.of());

        GraphDiff.Change change = only(GraphDiff.between(before, after));

        assertThat(change.subject()).isEqualTo(GraphDiff.Subject.ELEMENT);
        assertThat(change.verb()).isEqualTo(GraphDiff.Verb.ADDED);
        assertThat(change.label()).isEqualTo("Payment Service");
        assertThat(change.type()).isEqualTo("ApplicationComponent");
        assertThat(change.fields()).isEmpty();
    }

    /**
     * The point of an opaque identity (ADR-0016). Matching by name would report this as one
     * element gone and another arrived, and the history would lose the only fact worth keeping:
     * that they are the same thing.
     */
    @Test
    @DisplayName("a rename is one changed element, not a removal and an addition")
    void a_renamed_element_stays_one_object() {
        ModelGraph before = ModelGraph.of(List.of(element(PAYMENTS, "Payments")), List.of());
        ModelGraph after = ModelGraph.of(List.of(element(PAYMENTS, "Payment Service")), List.of());

        GraphDiff.Change change = only(GraphDiff.between(before, after));

        assertThat(change.verb()).isEqualTo(GraphDiff.Verb.CHANGED);
        assertThat(change.key()).isEqualTo(PAYMENTS);
        assertThat(change.fields()).containsExactly(new GraphDiff.FieldChange("name", "Payments", "Payment Service"));
    }

    @Test
    @DisplayName("a specialisation shows as a change of type, under the name the user reads")
    void applying_a_profile_changes_the_display_type() {
        ModelGraph before = ModelGraph.of(List.of(element(PAYMENTS, "Payment Service")), List.of());
        ModelGraph after = ModelGraph.of(
                List.of(new Element(PAYMENTS, "ApplicationComponent", "Microservice", "Payment Service", null, Map.of())),
                List.of());

        GraphDiff.Change change = only(GraphDiff.between(before, after));

        assertThat(change.fields())
                .containsExactly(new GraphDiff.FieldChange("type", "ApplicationComponent", "Microservice"));
    }

    @Test
    @DisplayName("properties are compared key by key, and a removed one reads as a value gone")
    void properties_are_compared_one_key_at_a_time() {
        ModelGraph before = ModelGraph.of(
                List.of(new Element(
                        PAYMENTS,
                        "ApplicationComponent",
                        null,
                        "Payment Service",
                        null,
                        Map.of("owner", "alice", "tag", "legacy"))),
                List.of());
        ModelGraph after = ModelGraph.of(
                List.of(new Element(
                        PAYMENTS, "ApplicationComponent", null, "Payment Service", null, Map.of("owner", "bob"))),
                List.of());

        GraphDiff.Change change = only(GraphDiff.between(before, after));

        assertThat(change.fields())
                .containsExactly(
                        new GraphDiff.FieldChange("property:owner", "alice", "bob"),
                        new GraphDiff.FieldChange("property:tag", "legacy", null));
    }

    @Test
    @DisplayName("a relationship names its endpoints, read from the state it belonged to")
    void a_removed_relationship_keeps_the_names_it_had() {
        Relationship publishes = Relationship.of(PUBLISHES, "Flow", PAYMENTS, KAFKA);
        ModelGraph before = ModelGraph.of(
                List.of(element(PAYMENTS, "Payment Service"), element(KAFKA, "Kafka")), List.of(publishes));
        ModelGraph after =
                ModelGraph.of(List.of(element(PAYMENTS, "Payment Service"), element(KAFKA, "Kafka")), List.of());

        GraphDiff.Change change = only(GraphDiff.between(before, after));

        assertThat(change.subject()).isEqualTo(GraphDiff.Subject.RELATIONSHIP);
        assertThat(change.verb()).isEqualTo(GraphDiff.Verb.REMOVED);
        assertThat(change.type()).isEqualTo("Flow");
        assertThat(change.source()).isEqualTo("Payment Service");
        assertThat(change.target()).isEqualTo("Kafka");
    }

    /**
     * Endpoints compare by identity and show by name. Comparing the names instead made renaming
     * one element report every relationship at it as changed — a commit of one rename reading as
     * a commit that touched half the landscape.
     */
    @Test
    @DisplayName("renaming an element leaves the relationships at it untouched")
    void a_rename_does_not_ripple_into_the_lines_around_it() {
        Relationship publishes = Relationship.of(PUBLISHES, "Flow", PAYMENTS, KAFKA);
        ModelGraph before = ModelGraph.of(
                List.of(element(PAYMENTS, "Payment Service"), element(KAFKA, "Kafka")), List.of(publishes));
        ModelGraph after =
                ModelGraph.of(List.of(element(PAYMENTS, "Payments"), element(KAFKA, "Kafka")), List.of(publishes));

        GraphDiff.Change change = only(GraphDiff.between(before, after));

        assertThat(change.subject()).isEqualTo(GraphDiff.Subject.ELEMENT);
        assertThat(change.fields()).containsExactly(new GraphDiff.FieldChange("name", "Payment Service", "Payments"));
    }

    @Test
    @DisplayName("what joined and what left a view are named, not counted")
    void view_membership_is_reported_by_name() {
        List<Element> elements =
                List.of(element(PAYMENTS, "Payment Service"), element(KAFKA, "Kafka"), element(NOTIFICATIONS, "Notifications"));
        ModelGraph before = ModelGraph.of(
                elements,
                List.of(),
                List.of(new View(CONTEXT, "Payments context", "layered", Set.of(PAYMENTS, KAFKA), Set.of())));
        ModelGraph after = ModelGraph.of(
                elements,
                List.of(),
                List.of(new View(CONTEXT, "Payments context", "layered", Set.of(PAYMENTS, NOTIFICATIONS), Set.of())));

        GraphDiff.Change change = only(GraphDiff.between(before, after));

        assertThat(change.subject()).isEqualTo(GraphDiff.Subject.VIEW);
        assertThat(change.fields())
                .containsExactly(
                        new GraphDiff.FieldChange("elements", null, "Notifications"),
                        new GraphDiff.FieldChange("elements", "Kafka", null));
    }

    @Test
    @DisplayName("a filter added to a view reads as the condition it states")
    void filters_are_described_rather_than_dumped() {
        View bare = new View(CONTEXT, "Payments context", "layered", Set.of(), Set.of());
        ModelGraph before = ModelGraph.of(List.of(), List.of(), List.of(bare));
        ModelGraph after = ModelGraph.of(
                List.of(), List.of(), List.of(bare.withFilters(Set.of(ViewFilter.layer("application")))));

        GraphDiff.Change change = only(GraphDiff.between(before, after));

        assertThat(change.fields())
                .containsExactly(new GraphDiff.FieldChange("filters", null, "layer=application"));
    }

    @Test
    @DisplayName("reversing the two states yields the inverse diff")
    void the_diff_runs_both_ways() {
        ModelGraph empty = ModelGraph.of(List.of(), List.of());
        ModelGraph one = ModelGraph.of(List.of(element(PAYMENTS, "Payment Service")), List.of());

        assertThat(GraphDiff.between(empty, one).summary()).isEqualTo(new GraphDiff.Summary(1, 0, 0));
        assertThat(GraphDiff.between(one, empty).summary()).isEqualTo(new GraphDiff.Summary(0, 1, 0));
    }

    @Test
    @DisplayName("changes are grouped by kind, so a commit reads elements first and views last")
    void changes_come_back_in_a_stable_order() {
        ModelGraph before = ModelGraph.of(List.of(), List.of());
        ModelGraph after = ModelGraph.of(
                List.of(element(PAYMENTS, "Payment Service"), element(KAFKA, "Kafka")),
                List.of(Relationship.of(PUBLISHES, "Flow", PAYMENTS, KAFKA)),
                List.of(View.of(CONTEXT, "Payments context", "layered")));

        List<GraphDiff.Subject> order =
                GraphDiff.between(before, after).changes().stream().map(GraphDiff.Change::subject).toList();

        assertThat(order)
                .containsExactly(
                        GraphDiff.Subject.ELEMENT,
                        GraphDiff.Subject.ELEMENT,
                        GraphDiff.Subject.RELATIONSHIP,
                        GraphDiff.Subject.VIEW);
        // Alphabetical within a kind: "Kafka" before "Payment Service".
        assertThat(GraphDiff.between(before, after).of(GraphDiff.Subject.ELEMENT).stream()
                        .map(GraphDiff.Change::label))
                .containsExactly("Kafka", "Payment Service");
    }
}
