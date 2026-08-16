package dev.archgrid.domain.metamodel;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A named subset of element types offered as the palette for a kind of diagram.
 *
 * <p>Viewpoints are how ArchiMate becomes usable rather than overwhelming: the model may
 * contain all sixty element types, but a given view offers ten. Model completeness and
 * palette completeness are different things (ADR-0002).
 *
 * @param concepts the element type concepts allowed on this viewpoint. An <em>empty</em> set
 *     means every concept is allowed — that is how the Layered viewpoint is expressed
 *     upstream, and it is a meaningful value, not missing data.
 */
public record Viewpoint(String code, String name, Set<String> concepts) {

    public Viewpoint {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(concepts, "concepts must not be null");
        concepts = Collections.unmodifiableSet(new LinkedHashSet<>(concepts));
    }

    /** True when this viewpoint places no restriction on which element types may appear. */
    public boolean allowsEveryConcept() {
        return concepts.isEmpty();
    }

    public boolean allows(String concept) {
        return concepts.isEmpty() || concepts.contains(concept);
    }
}
