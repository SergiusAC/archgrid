package dev.archgrid.domain.metamodel;

import java.util.Objects;

/**
 * One of the eleven ArchiMate relationship types.
 *
 * @param concept the upstream identifier, e.g. {@code Composition}
 * @param name the display name
 * @param key the single letter the permitted matrix uses for this type; keeping it makes the
 *     generated metamodel file diffable against upstream
 * @param category structural, dependency, dynamic or other
 * @param strength position in the structural strength ordering, 1 being the strongest, or
 *     {@link #NOT_STRUCTURAL} for the seven types the ordering does not apply to
 */
public record RelationshipType(String concept, String name, char key, RelationshipCategory category, int strength) {

    /** Strength value for relationship types outside the structural ordering. */
    public static final int NOT_STRUCTURAL = 0;

    public RelationshipType {
        Objects.requireNonNull(concept, "concept must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(category, "category must not be null");
        if (concept.isBlank()) {
            throw new IllegalArgumentException("concept must not be blank");
        }
        boolean structural = category == RelationshipCategory.STRUCTURAL;
        if (structural == (strength == NOT_STRUCTURAL)) {
            throw new IllegalArgumentException(
                    "structural relationship types must carry a strength and only those may: " + concept);
        }
    }

    public boolean isStructural() {
        return category == RelationshipCategory.STRUCTURAL;
    }

    /**
     * The weaker of two structural relationship types — the derived relationship of a chain,
     * under ArchiMate's weakest-link rule.
     *
     * @throws IllegalArgumentException if either type is not structural, because deriving
     *     across a non-structural link is a different rule and silently returning something
     *     plausible here would produce wrong diagrams
     */
    public static RelationshipType weakerOf(RelationshipType first, RelationshipType second) {
        if (!first.isStructural() || !second.isStructural()) {
            throw new IllegalArgumentException(
                    "the weakest-link rule applies to structural relationships only, got " + first.concept() + " and "
                            + second.concept());
        }
        return first.strength() >= second.strength() ? first : second;
    }

    @Override
    public String toString() {
        return concept;
    }
}
