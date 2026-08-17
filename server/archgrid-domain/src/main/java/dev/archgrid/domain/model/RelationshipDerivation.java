package dev.archgrid.domain.model;

import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipCategory;
import dev.archgrid.domain.metamodel.RelationshipType;
import dev.archgrid.domain.shared.Slug;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Computes the relationships a view should show between elements it displays, but which exist
 * in the model only as chains through elements it does not.
 *
 * <p>This is what makes a context diagram truthful. If {@code payment-service} contains
 * {@code payment-api}, and {@code payment-api} serves {@code notification-service}, then at the
 * context level Payment Service serves Notification Service — and a diagram that omitted that
 * line because no one drew it would be lying by omission.
 *
 * <p>Two rules, applied along a path whose intermediate elements are all hidden:
 *
 * <ul>
 *   <li>A chain of <strong>structural</strong> relationships derives the weakest of them.
 *   <li>A chain of structural relationships ending in one <strong>dependency or dynamic</strong>
 *       relationship derives that relationship.
 * </ul>
 *
 * <p>Nothing derives past a dependency: once the chain has left the structure it no longer
 * describes the same thing, and continuing would manufacture claims the model does not make.
 *
 * <p><strong>Containment counts as composition.</strong> An element's {@code parent} is
 * ArchiMate composition expressed as a field rather than a relationship row, and derivation
 * that ignored it would miss the most common case there is — the whole point of a context view.
 *
 * <p>The strength ordering this depends on is the one value in the metamodel not taken from
 * upstream and still unverified against the specification (see {@code metamodel/VERIFICATION.md}).
 * An error there would change which relationship is derived, never whether one is.
 */
public final class RelationshipDerivation {

    /**
     * How far a chain may run before we stop looking.
     *
     * <p>Paths longer than this are theoretically derivable and practically meaningless: a line
     * on a diagram justified by nine hops tells the reader nothing they can act on.
     */
    private static final int MAX_PATH = 6;

    private RelationshipDerivation() {}

    /**
     * @param visible the elements the view shows
     * @return derived relationships between visible elements, excluding any pair that already
     *     has an explicit relationship of the same type — an explicit line always wins
     */
    public static List<DerivedRelationship> derive(ModelGraph graph, Set<Slug> visible, Metamodel metamodel) {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(visible, "visible must not be null");
        Objects.requireNonNull(metamodel, "metamodel must not be null");

        Map<Slug, List<Edge>> outgoing = buildAdjacency(graph, metamodel);
        Set<String> explicit = new LinkedHashSet<>();
        for (Relationship relationship : graph.relationships()) {
            if (visible.contains(relationship.source()) && visible.contains(relationship.target())) {
                explicit.add(pairKey(relationship.source(), relationship.target(), relationship.conceptType()));
            }
        }

        Map<String, DerivedRelationship> found = new LinkedHashMap<>();
        for (Slug start : visible) {
            walk(start, visible, outgoing, explicit, found);
        }
        return List.copyOf(found.values());
    }

    private static void walk(
            Slug start,
            Set<Slug> visible,
            Map<Slug, List<Edge>> outgoing,
            Set<String> explicit,
            Map<String, DerivedRelationship> found) {

        Deque<Step> queue = new ArrayDeque<>();
        for (Edge edge : outgoing.getOrDefault(start, List.of())) {
            queue.add(new Step(edge.target(), edge.type(), List.of()));
        }

        while (!queue.isEmpty()) {
            Step step = queue.poll();

            if (visible.contains(step.at())) {
                // Reached another visible element. A path with no hidden middle is just the
                // explicit relationship itself and derives nothing.
                if (!step.via().isEmpty() && !step.at().equals(start)) {
                    String key = pairKey(start, step.at(), step.type().concept());
                    if (!explicit.contains(key)) {
                        found.putIfAbsent(key, new DerivedRelationship(start, step.at(), step.type(), step.via()));
                    }
                }
                continue;
            }

            if (step.via().size() >= MAX_PATH) {
                continue;
            }

            for (Edge edge : outgoing.getOrDefault(step.at(), List.of())) {
                if (step.via().contains(edge.target()) || edge.target().equals(start)) {
                    continue; // never revisit; a cycle would derive nothing new
                }
                Optional<RelationshipType> extended = extend(step.type(), edge.type());
                if (extended.isEmpty()) {
                    continue;
                }
                List<Slug> via = new ArrayList<>(step.via());
                via.add(step.at());
                queue.add(new Step(edge.target(), extended.get(), via));
            }
        }
    }

    /**
     * The relationship a two-link chain derives, if any.
     *
     * <p>Returns empty rather than guessing. A chain that continues past a dependency, or joins
     * two dependencies, describes no single relationship, and inventing one here would put a
     * line on a diagram that the model does not support.
     */
    private static Optional<RelationshipType> extend(RelationshipType soFar, RelationshipType next) {
        if (!isStructural(soFar)) {
            return Optional.empty();
        }
        if (isStructural(next)) {
            return Optional.of(RelationshipType.weakerOf(soFar, next));
        }
        if (next.category() == RelationshipCategory.DEPENDENCY || next.category() == RelationshipCategory.DYNAMIC) {
            return Optional.of(next);
        }
        // Specialization and the like: not part of the derivation rules.
        return Optional.empty();
    }

    private static boolean isStructural(RelationshipType type) {
        return type.category() == RelationshipCategory.STRUCTURAL;
    }

    /**
     * Explicit relationships plus containment, which is composition written as a field.
     *
     * <p>The parent edge runs child → parent as an <em>aggregation-strength</em> link in the
     * direction that matters here: what the child does, the parent is understood to do.
     */
    private static Map<Slug, List<Edge>> buildAdjacency(ModelGraph graph, Metamodel metamodel) {
        Map<Slug, List<Edge>> outgoing = new LinkedHashMap<>();
        for (Relationship relationship : graph.relationships()) {
            metamodel
                    .relationshipType(relationship.conceptType())
                    .ifPresent(type -> outgoing
                            .computeIfAbsent(relationship.source(), key -> new ArrayList<>())
                            .add(new Edge(relationship.target(), type)));
        }

        Optional<RelationshipType> composition = metamodel.relationshipType("Composition");
        if (composition.isPresent()) {
            for (Element element : graph.elements()) {
                element.parentKey()
                        .ifPresent(parent -> outgoing
                                .computeIfAbsent(parent, key -> new ArrayList<>())
                                .add(new Edge(element.key(), composition.get())));
            }
        }
        return outgoing;
    }

    private static String pairKey(Slug source, Slug target, String type) {
        return source.value() + "|" + target.value() + "|" + type;
    }

    private record Edge(Slug target, RelationshipType type) {}

    private record Step(Slug at, RelationshipType type, List<Slug> via) {}
}
