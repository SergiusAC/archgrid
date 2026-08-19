package dev.archgrid.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.archgrid.domain.metamodel.Aspect;
import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Layer;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipCategory;
import dev.archgrid.domain.metamodel.RelationshipType;
import dev.archgrid.domain.metamodel.c4.C4Level;
import dev.archgrid.domain.metamodel.c4.C4Mapping;
import dev.archgrid.domain.metamodel.c4.C4RelationshipIntent;
import dev.archgrid.domain.metamodel.c4.C4Shape;
import dev.archgrid.domain.shared.Slug;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The projection that makes one graph readable as C4 without storing a single C4 type. */
class C4ProjectionTest {

    private static final RelationshipType COMPOSITION =
            new RelationshipType("Composition", "Composition", 'c', RelationshipCategory.STRUCTURAL, 1);
    private static final RelationshipType AGGREGATION =
            new RelationshipType("Aggregation", "Aggregation", 'g', RelationshipCategory.STRUCTURAL, 2);
    private static final RelationshipType SERVING =
            new RelationshipType("Serving", "Serving", 'v', RelationshipCategory.DEPENDENCY, 0);

    private static final ElementType COMPONENT =
            new ElementType("ApplicationComponent", "Application Component", Layer.APPLICATION, Aspect.ACTIVE_STRUCTURE);
    private static final ElementType SERVICE =
            new ElementType("ApplicationService", "Application Service", Layer.APPLICATION, Aspect.BEHAVIOR);
    private static final ElementType NODE =
            new ElementType("Node", "Node", Layer.TECHNOLOGY, Aspect.ACTIVE_STRUCTURE);

    private static final Metamodel METAMODEL = Metamodel.builder()
            .version("3.2")
            .relationshipType(COMPOSITION)
            .relationshipType(AGGREGATION)
            .relationshipType(SERVING)
            .elementType(COMPONENT)
            .elementType(SERVICE)
            .elementType(NODE)
            .permit("ApplicationComponent", "ApplicationComponent", List.of(COMPOSITION, AGGREGATION, SERVING))
            .permit("ApplicationComponent", "ApplicationService", List.of(COMPOSITION, SERVING))
            .permit("ApplicationComponent", "Node", List.of(SERVING))
            .build();

    private static final C4Level CONTEXT =
            new C4Level("context", "System Context", 0, Set.of(Layer.BUSINESS, Layer.APPLICATION));
    private static final C4Level CONTAINER =
            new C4Level("container", "Container", 1, Set.of(Layer.BUSINESS, Layer.APPLICATION, Layer.TECHNOLOGY));

    private static final C4Mapping MAPPING = C4Mapping.builder()
            .shape("ApplicationComponent", C4Shape.BOX)
            .shape("Node", C4Shape.BOX)
            .level(CONTEXT)
            .level(CONTAINER)
            // The projection never asks about intents; the mapping insists on one, because a C4
            // arrow with no stated meaning still has to become some ArchiMate relationship.
            .intent(new C4RelationshipIntent("uses", "uses", SERVING, true))
            .defaultIntent("uses")
            .build(METAMODEL);

    private static Element element(String key, ElementType type) {
        return new Element(Slug.of(key), type.concept(), null, key, null, Map.of());
    }

    private static Relationship relationship(String key, String type, String source, String target) {
        return Relationship.of(Slug.of(key), type, Slug.of(source), Slug.of(target));
    }

    /** Containment as it is stored: a Composition from the whole to the part (ADR-0011). */
    private static Relationship contains(String whole, String part) {
        return relationship(whole + "-contains-" + part, "Composition", whole, part);
    }

    /** The other way a model says "part of": weaker, shareable, and just as much inside. */
    private static Relationship aggregates(String whole, String part) {
        return relationship(whole + "-aggregates-" + part, "Aggregation", whole, part);
    }

    private static List<String> keys(List<Slug> slugs) {
        return slugs.stream().map(Slug::value).toList();
    }

    @Test
    @DisplayName("a context view shows the system and its neighbours, not its insides")
    void context_hides_the_inside() {
        ModelGraph graph = ModelGraph.of(
                List.of(
                        element("payments", COMPONENT),
                        element("ledger", COMPONENT),
                        element("notifications", COMPONENT)),
                List.of(
                        contains("payments", "ledger"),
                        relationship("ledger-serves", "Serving", "ledger", "notifications")));

        C4Projection.Result result =
                C4Projection.project(graph, METAMODEL, MAPPING, Slug.of("payments"), CONTEXT).orElseThrow();

        // The neighbour is there because something *inside* the focus talks to it. That is the
        // whole reason a context diagram is worth drawing.
        assertThat(keys(result.shown())).containsExactlyInAnyOrder("payments", "notifications");
        assertThat(keys(result.shown())).doesNotContain("ledger");
    }

    @Test
    @DisplayName("a container view opens the focus one level down and no further")
    void container_shows_the_children() {
        ModelGraph graph = ModelGraph.of(
                List.of(
                        element("payments", COMPONENT),
                        element("ledger", COMPONENT),
                        element("ledger-core", COMPONENT)),
                List.of(contains("payments", "ledger"), contains("ledger", "ledger-core")));

        C4Projection.Result result =
                C4Projection.project(graph, METAMODEL, MAPPING, Slug.of("payments"), CONTAINER).orElseThrow();

        assertThat(keys(result.shown())).containsExactlyInAnyOrder("payments", "ledger");
        assertThat(keys(result.shown())).doesNotContain("ledger-core");
    }

    @Test
    @DisplayName("a neighbour is drawn as the system that holds it, not as its innards")
    void neighbours_roll_up_to_their_container() {
        ModelGraph graph = ModelGraph.of(
                List.of(
                        element("payments", COMPONENT),
                        element("crm", COMPONENT),
                        element("crm-api", COMPONENT)),
                List.of(
                        contains("crm", "crm-api"),
                        relationship("api-serves", "Serving", "crm-api", "payments")));

        C4Projection.Result result =
                C4Projection.project(graph, METAMODEL, MAPPING, Slug.of("payments"), CONTEXT).orElseThrow();

        // A context diagram that named the particular module of the neighbouring system which
        // happens to call you would be a context diagram of nothing.
        assertThat(keys(result.shown())).containsExactlyInAnyOrder("payments", "crm");
    }

    @Test
    @DisplayName("an aggregated part is inside its whole, like a composed one")
    void aggregation_counts_as_containment() {
        ModelGraph graph = ModelGraph.of(
                List.of(
                        element("processing", COMPONENT),
                        element("backoffice", COMPONENT),
                        element("mobile", COMPONENT)),
                List.of(
                        aggregates("processing", "backoffice"),
                        relationship("backoffice-serves", "Serving", "backoffice", "mobile")));

        // Composition and aggregation differ in exclusivity, not in membership: a part that may be
        // shared is still a part, and a context diagram that drew it beside its own system would
        // be answering a question nobody asked.
        C4Projection.Result context =
                C4Projection.project(graph, METAMODEL, MAPPING, Slug.of("processing"), CONTEXT).orElseThrow();
        assertThat(keys(context.shown())).containsExactlyInAnyOrder("processing", "mobile");

        C4Projection.Result container =
                C4Projection.project(graph, METAMODEL, MAPPING, Slug.of("processing"), CONTAINER).orElseThrow();
        assertThat(keys(container.shown())).containsExactlyInAnyOrder("processing", "backoffice", "mobile");
    }

    @Test
    @DisplayName("what C4 cannot draw is reported, not silently dropped")
    void the_unrepresentable_is_named() {
        ModelGraph graph = ModelGraph.of(
                List.of(element("payments", COMPONENT), element("billing", SERVICE)),
                List.of(relationship("serves", "Serving", "payments", "billing")));

        C4Projection.Result result =
                C4Projection.project(graph, METAMODEL, MAPPING, Slug.of("payments"), CONTEXT).orElseThrow();

        assertThat(keys(result.shown())).containsExactly("payments");
        assertThat(keys(result.omitted())).containsExactly("billing");
    }

    @Test
    @DisplayName("a level admits only its own layers, so technology stays off a context view")
    void a_level_admits_only_its_layers() {
        ModelGraph graph = ModelGraph.of(
                List.of(element("payments", COMPONENT), element("cluster", NODE)),
                List.of(relationship("runs-on", "Serving", "payments", "cluster")));

        assertThat(keys(C4Projection.project(graph, METAMODEL, MAPPING, Slug.of("payments"), CONTEXT)
                        .orElseThrow()
                        .shown()))
                .containsExactly("payments");
        assertThat(keys(C4Projection.project(graph, METAMODEL, MAPPING, Slug.of("payments"), CONTAINER)
                        .orElseThrow()
                        .shown()))
                .containsExactlyInAnyOrder("payments", "cluster");
    }

    @Test
    @DisplayName("a focus C4 cannot draw is refused rather than answered with an empty diagram")
    void a_focus_without_a_c4_form_is_refused() {
        ModelGraph graph = ModelGraph.of(List.of(element("billing", SERVICE)), List.of());

        assertThat(C4Projection.project(graph, METAMODEL, MAPPING, Slug.of("billing"), CONTEXT))
                .isEqualTo(Optional.empty());
    }
}
