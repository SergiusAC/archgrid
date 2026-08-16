package dev.archgrid.domain.metamodel;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The answer to "may this relationship exist between these two element types".
 *
 * <p>A verdict is a sealed hierarchy rather than a boolean because the three outcomes call
 * for three different responses: proceed, tell the user what is allowed instead, or report a
 * type that does not exist at all. Collapsing them into {@code false} is how a product ends
 * up saying "invalid" and teaching nobody.
 */
public sealed interface RelationshipVerdict {

    boolean isPermitted();

    /** A sentence suitable for showing to an architect. */
    String explain();

    record Permitted(ElementType source, ElementType target, RelationshipType relationship)
            implements RelationshipVerdict {

        public Permitted {
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(relationship, "relationship must not be null");
        }

        @Override
        public boolean isPermitted() {
            return true;
        }

        @Override
        public String explain() {
            return "%s from %s to %s is permitted."
                    .formatted(relationship.name(), source.name(), target.name());
        }
    }

    /**
     * The types exist, but not in this combination.
     *
     * @param alternatives every relationship type that <em>would</em> be permitted between
     *     this pair, in the order the metamodel declares them. Possibly empty.
     */
    record NotPermitted(
            ElementType source, ElementType target, RelationshipType relationship, Set<RelationshipType> alternatives)
            implements RelationshipVerdict {

        public NotPermitted {
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(relationship, "relationship must not be null");
            Objects.requireNonNull(alternatives, "alternatives must not be null");
            alternatives = Collections.unmodifiableSet(new LinkedHashSet<>(alternatives));
        }

        @Override
        public boolean isPermitted() {
            return false;
        }

        @Override
        public String explain() {
            String head = "%s is not permitted from %s to %s."
                    .formatted(relationship.name(), source.name(), target.name());
            if (alternatives.isEmpty()) {
                return head + " No relationship type is permitted between these two.";
            }
            return head + " Permitted instead: "
                    + alternatives.stream().map(RelationshipType::name).collect(Collectors.joining(", ")) + ".";
        }
    }

    /**
     * A concept name that is not part of the metamodel at all — a typo, or data from a newer
     * version of the language than this installation carries.
     */
    record UnknownConcept(String concept, Role role) implements RelationshipVerdict {

        public enum Role {
            SOURCE,
            TARGET,
            RELATIONSHIP
        }

        public UnknownConcept {
            Objects.requireNonNull(concept, "concept must not be null");
            Objects.requireNonNull(role, "role must not be null");
        }

        @Override
        public boolean isPermitted() {
            return false;
        }

        @Override
        public String explain() {
            return "'%s' is not a known %s type in this metamodel."
                    .formatted(concept, role.name().toLowerCase(java.util.Locale.ROOT));
        }
    }
}
