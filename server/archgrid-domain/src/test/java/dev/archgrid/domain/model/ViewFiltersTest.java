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

/** Faceted filtering: alternatives within a kind, all kinds together. */
class ViewFiltersTest {

    private static final ElementType COMPONENT =
            new ElementType("ApplicationComponent", "Application Component", Layer.APPLICATION, Aspect.ACTIVE_STRUCTURE);
    private static final ElementType NODE =
            new ElementType("Node", "Node", Layer.TECHNOLOGY, Aspect.ACTIVE_STRUCTURE);
    private static final ElementType ACTOR =
            new ElementType("BusinessActor", "Business Actor", Layer.BUSINESS, Aspect.ACTIVE_STRUCTURE);

    private static final Metamodel METAMODEL = Metamodel.builder()
            .version("3.2")
            // Filtering never asks about relationships; the metamodel insists on having some.
            .relationshipType(new RelationshipType("Serving", "Serving", 'v', RelationshipCategory.DEPENDENCY, 0))
            .elementType(COMPONENT)
            .elementType(NODE)
            .elementType(ACTOR)
            .build();

    private static Element element(String key, ElementType type, Map<String, String> properties) {
        return new Element(Slug.of(key), type.concept(), null, key, null, properties);
    }

    private static ModelGraph graph() {
        return ModelGraph.of(
                List.of(
                        element("api", COMPONENT, Map.of("tag", "legacy")),
                        element("ledger", COMPONENT, Map.of("owner", "payments")),
                        element("cluster", NODE, Map.of("tag", "legacy")),
                        element("clerk", ACTOR, Map.of())),
                List.of());
    }

    private static View view(ViewFilter... filters) {
        return new View(
                Slug.of("v"),
                "View",
                "layered",
                Set.of(Slug.of("api"), Slug.of("ledger"), Slug.of("cluster"), Slug.of("clerk")),
                Set.of(filters));
    }

    private static List<String> keys(Set<Slug> slugs) {
        return slugs.stream().map(Slug::value).toList();
    }

    @Test
    @DisplayName("no filters, no filtering")
    void an_unfiltered_view_shows_its_members() {
        assertThat(keys(ViewFilters.visible(graph(), view(), METAMODEL)))
                .containsExactlyInAnyOrder("api", "ledger", "cluster", "clerk");
    }

    @Test
    @DisplayName("two filters of one kind are alternatives")
    void filters_of_one_kind_widen() {
        Set<Slug> visible =
                ViewFilters.visible(graph(), view(ViewFilter.layer("application"), ViewFilter.layer("business")), METAMODEL);

        // "Application or business" — the other reading, where every filter narrows, would make a
        // second layer produce an empty diagram, which nobody has ever meant by ticking two boxes.
        assertThat(keys(visible)).containsExactlyInAnyOrder("api", "ledger", "clerk");
    }

    @Test
    @DisplayName("filters of different kinds must all hold")
    void filters_of_different_kinds_narrow() {
        Set<Slug> visible = ViewFilters.visible(
                graph(), view(ViewFilter.layer("application"), ViewFilter.property("tag", "legacy")), METAMODEL);

        assertThat(keys(visible)).containsExactly("api");
    }

    @Test
    @DisplayName("a property filter with no value asks for everything that has the property")
    void a_property_filter_without_a_value_matches_any() {
        assertThat(keys(ViewFilters.visible(graph(), view(ViewFilter.property("tag", null)), METAMODEL)))
                .containsExactlyInAnyOrder("api", "cluster");
    }

    @Test
    @DisplayName("filtering never touches membership")
    void filtering_leaves_membership_alone() {
        View filtered = view(ViewFilter.layer("technology"));

        assertThat(keys(ViewFilters.visible(graph(), filtered, METAMODEL))).containsExactly("cluster");
        // Which is what makes removing a filter free: the elements never left, so neither did
        // their coordinates (ADR-0003).
        assertThat(filtered.elements()).hasSize(4);
    }
}
