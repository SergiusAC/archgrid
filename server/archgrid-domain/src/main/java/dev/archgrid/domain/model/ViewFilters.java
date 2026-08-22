package dev.archgrid.domain.model;

import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.shared.Id;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a view draws once its filters are applied.
 *
 * <p>Faceted, in the way every filter interface anyone has used behaves: within one kind the
 * conditions are alternatives, across kinds they must all hold. Two layer filters mean "business
 * or application"; a layer filter and a property filter mean "application layer, and tagged
 * legacy". The other reading — everything an alternative — makes each filter you add widen the
 * diagram, which is not what the word means.
 *
 * <p>Filtering happens before relationships are worked out, and that is the whole value of doing
 * it here rather than by hiding boxes in the browser: a view filtered to the application layer
 * derives the application-level lines that run through the technology it no longer shows
 * (ADR-0003 — the derivation engine already knows how to lift a path through what is not drawn).
 */
public final class ViewFilters {

    private ViewFilters() {}

    /**
     * The elements of this view that survive its filters, in membership order.
     *
     * <p>An unfiltered view returns its membership unchanged, which is the overwhelmingly common
     * case and costs nothing.
     */
    public static Set<Id> visible(ModelGraph graph, View view, Metamodel metamodel) {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(view, "view must not be null");
        Objects.requireNonNull(metamodel, "metamodel must not be null");
        if (view.filters().isEmpty()) {
            return view.elements();
        }

        Map<ViewFilter.Kind, List<ViewFilter>> byKind = new EnumMap<>(ViewFilter.Kind.class);
        for (ViewFilter filter : view.filters()) {
            byKind.computeIfAbsent(filter.kind(), kind -> new java.util.ArrayList<>()).add(filter);
        }

        Set<Id> visible = new LinkedHashSet<>();
        for (Id key : view.elements()) {
            graph.element(key).filter(element -> matches(element, byKind, metamodel)).ifPresent(element -> visible.add(key));
        }
        return visible;
    }

    private static boolean matches(
            Element element, Map<ViewFilter.Kind, List<ViewFilter>> byKind, Metamodel metamodel) {
        for (Map.Entry<ViewFilter.Kind, List<ViewFilter>> facet : byKind.entrySet()) {
            boolean any = facet.getValue().stream().anyMatch(filter -> matches(element, filter, metamodel));
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(Element element, ViewFilter filter, Metamodel metamodel) {
        return switch (filter.kind()) {
            case LAYER -> metamodel
                    .elementType(element.conceptType())
                    // An element whose type the metamodel does not know cannot be said to be in
                    // any layer, so a layer filter excludes it rather than guessing.
                    .map(type -> type.layer().code().equals(filter.value()))
                    .orElse(false);
            case PROPERTY -> {
                String held = element.properties().get(filter.name());
                // A null value asks "has this property at all", which is how "everything with an
                // owner" is expressed without inventing a wildcard.
                yield held != null && (filter.value() == null || held.equals(filter.value()));
            }
        };
    }
}
