package dev.archgrid.domain.model;

import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.c4.C4Level;
import dev.archgrid.domain.metamodel.c4.C4Mapping;
import dev.archgrid.domain.shared.Slug;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One graph, seen as C4.
 *
 * <p>Nothing here is stored and no element carries a C4 type (ADR-0007). A projection is three
 * parameters — a focus, a level, and the mapping — and the answer is a set of elements. Whether
 * something is a Software System or a Container is <em>depth relative to the focus</em>: the same
 * service is a container inside its platform and a system seen from the next platform along, and
 * a stored "C4 type" would have to call one of those readings wrong.
 *
 * <p>The two hard parts are both about what <em>not</em> to show.
 *
 * <p><strong>Neighbours are rolled up to what contains them.</strong> A context diagram that drew
 * the particular module of the neighbouring platform that happens to call you would be a context
 * diagram of nothing. So anything outside the focus is represented by the outermost element that
 * holds it, and the lines to it are whatever derivation makes of the paths underneath (ADR-0011:
 * containment is a Composition row, so this is an ordinary walk of the graph).
 *
 * <p><strong>Most of ArchiMate is not representable.</strong> C4 has no behaviour, no interfaces,
 * no motivation, no strategy; that is a property of the two languages, not a gap in the table. So
 * what cannot be drawn is returned as a list rather than silently dropped — a diagram has to be
 * able to say "and these are not shown here".
 */
public final class C4Projection {

    private C4Projection() {}

    /**
     * @param shown what the diagram draws, focus first
     * @param omitted elements that belong to the picture but have no C4 form — the honest answer
     *     for behaviour, interfaces and everything else the notation does not carry
     */
    public record Result(Slug focus, C4Level level, List<Slug> shown, List<Slug> omitted) {

        public Result {
            Objects.requireNonNull(focus, "focus must not be null");
            Objects.requireNonNull(level, "level must not be null");
            shown = List.copyOf(shown);
            omitted = List.copyOf(omitted);
        }
    }

    /**
     * Projects the graph around {@code focus}.
     *
     * @return empty when the focus itself has no C4 form. A C4 diagram is drawn around a system,
     *     and a projection centred on something the notation cannot draw is not a diagram with one
     *     box missing — it is not a diagram. The caller is expected to say so.
     */
    public static Optional<Result> project(
            ModelGraph graph, Metamodel metamodel, C4Mapping mapping, Slug focus, C4Level level) {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(metamodel, "metamodel must not be null");
        Objects.requireNonNull(mapping, "mapping must not be null");
        Objects.requireNonNull(focus, "focus must not be null");
        Objects.requireNonNull(level, "level must not be null");

        if (graph.element(focus).isEmpty() || !isVisible(graph, metamodel, mapping, focus, level)) {
            return Optional.empty();
        }

        // Everything the focus contains, however deep: the outside world may well touch something
        // buried, and a context diagram exists precisely to show that it does.
        Set<Slug> within = new LinkedHashSet<>(subtree(graph, focus, Integer.MAX_VALUE));
        within.add(focus);
        // What the level draws of the inside: depth 0 shows none of it, 1 the children, 2 theirs.
        Set<Slug> inside = subtree(graph, focus, level.depth());

        Set<Slug> candidates = new LinkedHashSet<>();
        candidates.add(focus);
        candidates.addAll(inside);
        for (Slug neighbour : neighbours(graph, within)) {
            candidates.add(outermost(graph, neighbour));
        }

        List<Slug> shown = new ArrayList<>();
        List<Slug> omitted = new ArrayList<>();
        for (Slug candidate : candidates) {
            if (isVisible(graph, metamodel, mapping, candidate, level)) {
                shown.add(candidate);
            } else {
                omitted.add(candidate);
            }
        }
        return Optional.of(new Result(focus, level, shown, omitted));
    }

    /** Elements outside the focus that touch anything within it, at any depth. */
    private static Set<Slug> neighbours(ModelGraph graph, Set<Slug> subtree) {
        Set<Slug> found = new LinkedHashSet<>();
        for (Relationship relationship : graph.relationships()) {
            boolean sourceInside = subtree.contains(relationship.source());
            boolean targetInside = subtree.contains(relationship.target());
            if (sourceInside && !targetInside) {
                found.add(relationship.target());
            } else if (targetInside && !sourceInside) {
                found.add(relationship.source());
            }
        }
        return found;
    }

    /**
     * What contains this element at the top, or the element itself when nothing does.
     *
     * <p>Rolling a neighbour up to its outermost container is what keeps a context diagram about
     * systems. Where the chain has somehow become a cycle the walk stops rather than hangs; the
     * validator reports that separately.
     */
    private static Slug outermost(ModelGraph graph, Slug key) {
        List<Element> ancestors = graph.ancestorsOf(key);
        return ancestors.isEmpty() ? key : ancestors.get(ancestors.size() - 1).key();
    }

    /** The focus's descendants down to {@code depth} composition steps; empty at depth 0. */
    private static Set<Slug> subtree(ModelGraph graph, Slug root, int depth) {
        Set<Slug> found = new LinkedHashSet<>();
        List<Slug> frontier = List.of(root);
        for (int step = 0; step < depth && !frontier.isEmpty(); step++) {
            List<Slug> next = new ArrayList<>();
            for (Slug at : frontier) {
                for (Element child : graph.childrenOf(at)) {
                    // A cycle would otherwise walk for ever; `found` is the guard.
                    if (found.add(child.key())) {
                        next.add(child.key());
                    }
                }
            }
            frontier = next;
        }
        return found;
    }

    private static boolean isVisible(
            ModelGraph graph, Metamodel metamodel, C4Mapping mapping, Slug key, C4Level level) {
        return graph.element(key)
                .flatMap(element -> metamodel.elementType(element.conceptType()))
                .map((ElementType type) -> mapping.isVisibleAt(type, level))
                .orElse(false);
    }
}
