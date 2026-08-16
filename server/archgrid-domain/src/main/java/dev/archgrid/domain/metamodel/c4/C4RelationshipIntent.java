package dev.archgrid.domain.metamodel.c4;

import dev.archgrid.domain.metamodel.RelationshipType;
import java.util.Objects;

/**
 * What a C4 arrow means, and the ArchiMate relationship it becomes.
 *
 * @param reverse whether the ArchiMate relationship runs the other way round.
 *     <p><strong>This flag is the point of this record.</strong> C4 draws an arrow from
 *     consumer to provider — {@code Notification Service -> Kafka}, read as "uses". ArchiMate's
 *     Serving points from provider to consumer — {@code Kafka -> Notification Service}, read
 *     as "serves". An import that ignores this produces a model in which every dependency
 *     faces backwards, while the diagram still looks entirely plausible and impact analysis
 *     answers the exact opposite of the truth. See ADR-0007.
 */
public record C4RelationshipIntent(String intent, String description, RelationshipType relationship, boolean reverse) {

    public C4RelationshipIntent {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(relationship, "relationship must not be null");
    }

    /**
     * Applies this intent to a C4 arrow, returning the ArchiMate endpoints in the correct
     * order.
     *
     * @param c4Source the element the C4 arrow leaves
     * @param c4Target the element the C4 arrow enters
     */
    public Directed apply(String c4Source, String c4Target) {
        Objects.requireNonNull(c4Source, "c4Source must not be null");
        Objects.requireNonNull(c4Target, "c4Target must not be null");
        return reverse
                ? new Directed(c4Target, c4Source, relationship)
                : new Directed(c4Source, c4Target, relationship);
    }

    /** An ArchiMate relationship with its endpoints already in ArchiMate's direction. */
    public record Directed(String source, String target, RelationshipType relationship) {}
}
