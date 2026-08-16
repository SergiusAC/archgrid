package dev.archgrid.domain.metamodel;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The loaded ArchiMate metamodel: element types, relationship types, the permitted-relationship
 * matrix and the viewpoint palettes.
 *
 * <p>Immutable and free of any framework, so the rules that decide whether a model is valid can
 * be tested without a Spring context or a database — the reason {@code archgrid-domain} exists
 * as a separate module at all.
 *
 * <p>Construct through {@link #builder()}; the builder validates internal consistency, so an
 * instance of this class is by construction a coherent metamodel.
 */
public final class Metamodel {

    private final String language;
    private final String version;
    private final Map<String, ElementType> elementTypes;
    private final Map<String, RelationshipType> relationshipTypes;
    private final Map<String, Map<String, Set<RelationshipType>>> permitted;
    private final Map<String, Viewpoint> viewpoints;

    private Metamodel(Builder builder) {
        this.language = builder.language;
        this.version = builder.version;
        this.elementTypes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.elementTypes));
        this.relationshipTypes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.relationshipTypes));
        this.viewpoints = Collections.unmodifiableMap(new LinkedHashMap<>(builder.viewpoints));

        Map<String, Map<String, Set<RelationshipType>>> copy = new LinkedHashMap<>();
        builder.permitted.forEach((source, row) -> {
            Map<String, Set<RelationshipType>> targets = new LinkedHashMap<>();
            row.forEach((target, types) -> targets.put(target, Collections.unmodifiableSet(new LinkedHashSet<>(types))));
            copy.put(source, Collections.unmodifiableMap(targets));
        });
        this.permitted = Collections.unmodifiableMap(copy);
    }

    public String language() {
        return language;
    }

    public String version() {
        return version;
    }

    public Collection<ElementType> elementTypes() {
        return elementTypes.values();
    }

    public Collection<RelationshipType> relationshipTypes() {
        return relationshipTypes.values();
    }

    public Collection<Viewpoint> viewpoints() {
        return viewpoints.values();
    }

    public Optional<ElementType> elementType(String concept) {
        return Optional.ofNullable(elementTypes.get(concept));
    }

    public Optional<RelationshipType> relationshipType(String concept) {
        return Optional.ofNullable(relationshipTypes.get(concept));
    }

    public Optional<Viewpoint> viewpoint(String code) {
        return Optional.ofNullable(viewpoints.get(code));
    }

    /** Every relationship type permitted from {@code source} to {@code target}, possibly empty. */
    public Set<RelationshipType> permittedBetween(String source, String target) {
        return permitted.getOrDefault(source, Map.of()).getOrDefault(target, Set.of());
    }

    /**
     * The central question of this whole module.
     *
     * <p>Note what this does <em>not</em> answer: whether a relationship in an actual model is
     * explicit or derived. The matrix merges the two, so that distinction is computed from the
     * model graph, not looked up here.
     */
    public RelationshipVerdict check(String sourceConcept, String targetConcept, String relationshipConcept) {
        ElementType source = elementTypes.get(sourceConcept);
        if (source == null) {
            return new RelationshipVerdict.UnknownConcept(sourceConcept, RelationshipVerdict.UnknownConcept.Role.SOURCE);
        }
        ElementType target = elementTypes.get(targetConcept);
        if (target == null) {
            return new RelationshipVerdict.UnknownConcept(targetConcept, RelationshipVerdict.UnknownConcept.Role.TARGET);
        }
        RelationshipType relationship = relationshipTypes.get(relationshipConcept);
        if (relationship == null) {
            return new RelationshipVerdict.UnknownConcept(
                    relationshipConcept, RelationshipVerdict.UnknownConcept.Role.RELATIONSHIP);
        }

        Set<RelationshipType> allowed = permittedBetween(sourceConcept, targetConcept);
        if (allowed.contains(relationship)) {
            return new RelationshipVerdict.Permitted(source, target, relationship);
        }
        return new RelationshipVerdict.NotPermitted(source, target, relationship, allowed);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String language = "archimate";
        private String version = "";
        private final Map<String, ElementType> elementTypes = new LinkedHashMap<>();
        private final Map<String, RelationshipType> relationshipTypes = new LinkedHashMap<>();
        private final Map<String, Map<String, Set<RelationshipType>>> permitted = new LinkedHashMap<>();
        private final Map<String, Viewpoint> viewpoints = new LinkedHashMap<>();

        private Builder() {}

        public Builder language(String value) {
            this.language = Objects.requireNonNull(value, "language must not be null");
            return this;
        }

        public Builder version(String value) {
            this.version = Objects.requireNonNull(value, "version must not be null");
            return this;
        }

        public Builder elementType(ElementType type) {
            Objects.requireNonNull(type, "element type must not be null");
            if (elementTypes.putIfAbsent(type.concept(), type) != null) {
                throw new IllegalArgumentException("duplicate element type: " + type.concept());
            }
            return this;
        }

        public Builder relationshipType(RelationshipType type) {
            Objects.requireNonNull(type, "relationship type must not be null");
            if (relationshipTypes.putIfAbsent(type.concept(), type) != null) {
                throw new IllegalArgumentException("duplicate relationship type: " + type.concept());
            }
            return this;
        }

        public Builder permit(String source, String target, List<RelationshipType> types) {
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(types, "types must not be null");
            permitted.computeIfAbsent(source, key -> new LinkedHashMap<>())
                    .put(target, new LinkedHashSet<>(types));
            return this;
        }

        public Builder viewpoint(Viewpoint viewpoint) {
            Objects.requireNonNull(viewpoint, "viewpoint must not be null");
            if (viewpoints.putIfAbsent(viewpoint.code(), viewpoint) != null) {
                throw new IllegalArgumentException("duplicate viewpoint: " + viewpoint.code());
            }
            return this;
        }

        /**
         * Checks that the parts refer only to each other before handing out an instance.
         *
         * <p>This is the guard ADR-0002 asks for: a metamodel file that references a concept it
         * never declares must fail at startup, loudly, rather than produce a validator that
         * quietly permits everything involving the missing type.
         */
        public Metamodel build() {
            if (elementTypes.isEmpty()) {
                throw new IllegalStateException("metamodel declares no element types");
            }
            if (relationshipTypes.isEmpty()) {
                throw new IllegalStateException("metamodel declares no relationship types");
            }

            permitted.forEach((source, row) -> {
                requireDeclaredElement(source, "permitted-relationship source");
                row.keySet().forEach(target -> requireDeclaredElement(target, "permitted-relationship target"));
            });

            viewpoints.values().forEach(viewpoint -> viewpoint.concepts()
                    .forEach(concept -> requireDeclaredElement(concept, "viewpoint " + viewpoint.code())));

            long structural = relationshipTypes.values().stream()
                    .filter(RelationshipType::isStructural)
                    .count();
            long distinctStrengths = relationshipTypes.values().stream()
                    .filter(RelationshipType::isStructural)
                    .map(RelationshipType::strength)
                    .distinct()
                    .count();
            if (structural != distinctStrengths) {
                throw new IllegalStateException(
                        "structural relationship types must have distinct strengths; the weakest-link rule is "
                                + "ambiguous otherwise");
            }

            return new Metamodel(this);
        }

        private void requireDeclaredElement(String concept, String where) {
            if (!elementTypes.containsKey(concept)) {
                throw new IllegalStateException("undeclared element type '" + concept + "' referenced by " + where);
            }
        }
    }
}
