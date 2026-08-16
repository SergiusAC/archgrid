package dev.archgrid.domain.metamodel;

import java.util.Objects;

/**
 * One of the ArchiMate element types.
 *
 * @param concept the upstream identifier, e.g. {@code ApplicationComponent}. This is also the
 *     name used by The Open Group exchange format, which is why it is kept verbatim rather
 *     than normalised: it is the join key against every other tool in this ecosystem.
 * @param name the display name, e.g. {@code Application Component}
 * @param layer which layer it belongs to
 * @param aspect which aspect it expresses, {@link Aspect#NONE} where the classification does
 *     not apply
 */
public record ElementType(String concept, String name, Layer layer, Aspect aspect) {

    public ElementType {
        Objects.requireNonNull(concept, "concept must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(layer, "layer must not be null");
        Objects.requireNonNull(aspect, "aspect must not be null");
        if (concept.isBlank()) {
            throw new IllegalArgumentException("concept must not be blank");
        }
    }

    /**
     * True for the pseudo-types that carry relationships rather than represent elements.
     *
     * <p>{@code Junction} joins relationships; {@code Relationship} exists because ArchiMate
     * permits a relationship to target another relationship. Both appear in the permitted
     * matrix and neither belongs in an element palette.
     */
    public boolean isPseudoElement() {
        return "Junction".equals(concept) || "Relationship".equals(concept);
    }

    @Override
    public String toString() {
        return concept;
    }
}
