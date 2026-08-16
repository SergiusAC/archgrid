package dev.archgrid.domain.metamodel;

/**
 * The category a relationship type belongs to, taken from the upstream type hierarchy.
 *
 * <p>This matters for more than documentation: relationship derivation applies to
 * {@link #STRUCTURAL} chains under the weakest-link rule, and a view that shows derived
 * relationships needs to know which chains are eligible.
 *
 * <p>The ordering <em>within</em> {@link #STRUCTURAL} — which of composition, aggregation,
 * assignment and realization is the weaker — is not encoded upstream and is carried
 * separately on {@link RelationshipType#strength()}, where it is marked unverified.
 */
public enum RelationshipCategory {
    /** Composition, aggregation, assignment, realization. */
    STRUCTURAL("structural"),
    /** Serving, access, influence, association. */
    DEPENDENCY("dependency"),
    /** Triggering, flow. */
    DYNAMIC("dynamic"),
    /** Specialization. */
    OTHER("other");

    private final String code;

    RelationshipCategory(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static RelationshipCategory fromCode(String code) {
        for (RelationshipCategory category : values()) {
            if (category.code.equals(code)) {
                return category;
            }
        }
        throw new IllegalArgumentException("unknown relationship category code: " + code);
    }
}
