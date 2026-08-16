package dev.archgrid.domain.metamodel.c4;

import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Metamodel;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The C4 projection: how ArchiMate is shown as C4, and what C4 becomes when authored or
 * imported.
 *
 * <p>Holds no state about any particular model. It answers questions about <em>types</em>;
 * turning a graph into an actual C4 diagram is the job of the view projection in phase 4,
 * which walks containment using {@link C4Level#depth()}.
 */
public final class C4Mapping {

    private final Map<String, C4Shape> shapes;
    private final Map<String, C4Level> levels;
    private final Map<String, C4ElementRule> authoring;
    private final Map<String, C4RelationshipIntent> intents;
    private final String defaultIntent;

    private C4Mapping(Builder builder) {
        this.shapes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.shapes));
        this.levels = Collections.unmodifiableMap(new LinkedHashMap<>(builder.levels));
        this.authoring = Collections.unmodifiableMap(new LinkedHashMap<>(builder.authoring));
        this.intents = Collections.unmodifiableMap(new LinkedHashMap<>(builder.intents));
        this.defaultIntent = builder.defaultIntent;
    }

    /**
     * How this element type renders in C4.
     *
     * <p>Returns {@link C4Shape#OMITTED} for anything not explicitly mapped, which is the
     * correct answer for most of ArchiMate rather than a gap in the table.
     */
    public C4Shape shapeOf(String concept) {
        return shapes.getOrDefault(concept, C4Shape.OMITTED);
    }

    public C4Shape shapeOf(ElementType type) {
        return shapeOf(Objects.requireNonNull(type, "type must not be null").concept());
    }

    /** True when this element type may appear at this C4 level: it has a shape and its layer is admitted. */
    public boolean isVisibleAt(ElementType type, C4Level level) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(level, "level must not be null");
        return shapeOf(type) != C4Shape.OMITTED && level.admits(type.layer());
    }

    public Optional<C4Level> level(String code) {
        return Optional.ofNullable(levels.get(code));
    }

    public Collection<C4Level> levels() {
        return levels.values();
    }

    public Optional<C4ElementRule> authoringRule(String c4Concept) {
        return Optional.ofNullable(authoring.get(c4Concept));
    }

    public Collection<C4ElementRule> authoringRules() {
        return authoring.values();
    }

    public Optional<C4RelationshipIntent> intent(String intent) {
        return Optional.ofNullable(intents.get(intent));
    }

    public Collection<C4RelationshipIntent> intents() {
        return intents.values();
    }

    /** The intent to assume for a plain C4 arrow with no stated meaning. */
    public C4RelationshipIntent defaultIntent() {
        return intents.get(defaultIntent);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<String, C4Shape> shapes = new LinkedHashMap<>();
        private final Map<String, C4Level> levels = new LinkedHashMap<>();
        private final Map<String, C4ElementRule> authoring = new LinkedHashMap<>();
        private final Map<String, C4RelationshipIntent> intents = new LinkedHashMap<>();
        private String defaultIntent;

        private Builder() {}

        public Builder shape(String concept, C4Shape shape) {
            Objects.requireNonNull(concept, "concept must not be null");
            Objects.requireNonNull(shape, "shape must not be null");
            if (shape == C4Shape.OMITTED) {
                throw new IllegalArgumentException(
                        "omitted is the default and must not be declared; listing it invites the table and the "
                                + "code to disagree about what is intentional: " + concept);
            }
            C4Shape existing = shapes.putIfAbsent(concept, shape);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "concept " + concept + " is mapped to both " + existing + " and " + shape);
            }
            return this;
        }

        public Builder level(C4Level level) {
            Objects.requireNonNull(level, "level must not be null");
            if (levels.putIfAbsent(level.code(), level) != null) {
                throw new IllegalArgumentException("duplicate C4 level: " + level.code());
            }
            return this;
        }

        public Builder authoringRule(C4ElementRule rule) {
            Objects.requireNonNull(rule, "rule must not be null");
            if (authoring.putIfAbsent(rule.c4Concept(), rule) != null) {
                throw new IllegalArgumentException("duplicate authoring rule: " + rule.c4Concept());
            }
            return this;
        }

        public Builder intent(C4RelationshipIntent intent) {
            Objects.requireNonNull(intent, "intent must not be null");
            if (intents.putIfAbsent(intent.intent(), intent) != null) {
                throw new IllegalArgumentException("duplicate relationship intent: " + intent.intent());
            }
            return this;
        }

        public Builder defaultIntent(String intent) {
            this.defaultIntent = Objects.requireNonNull(intent, "default intent must not be null");
            return this;
        }

        /**
         * Validates the mapping against the metamodel it claims to map.
         *
         * <p>Every concept named here must exist in the loaded ArchiMate metamodel. Without
         * this check a typo, or a concept renamed by an upstream update, degrades silently into
         * an element that is never drawn — a C4 view that is quietly missing a box, which is
         * far worse than one that fails to open.
         */
        public C4Mapping build(Metamodel metamodel) {
            Objects.requireNonNull(metamodel, "metamodel must not be null");

            shapes.keySet().forEach(concept -> requireElementType(metamodel, concept, "rendering"));
            authoring.values().forEach(rule -> {
                requireElementType(metamodel, rule.defaultType().concept(), "authoring." + rule.c4Concept());
                rule.discriminators()
                        .forEach(discriminator -> requireElementType(
                                metamodel,
                                discriminator.type().concept(),
                                "authoring." + rule.c4Concept() + " discriminator"));
            });
            intents.values()
                    .forEach(intent -> requireRelationshipType(
                            metamodel, intent.relationship().concept(), "intent." + intent.intent()));

            if (levels.isEmpty()) {
                throw new IllegalStateException("the C4 mapping declares no levels");
            }
            if (defaultIntent == null || !intents.containsKey(defaultIntent)) {
                throw new IllegalStateException("default relationship intent '" + defaultIntent + "' is not declared");
            }

            List<Integer> depths = levels.values().stream().map(C4Level::depth).sorted().toList();
            if (depths.stream().distinct().count() != depths.size()) {
                throw new IllegalStateException("two C4 levels claim the same depth; the projection would be ambiguous");
            }

            return new C4Mapping(this);
        }

        private static void requireElementType(Metamodel metamodel, String concept, String where) {
            if (metamodel.elementType(concept).isEmpty()) {
                throw new IllegalStateException(
                        "C4 mapping references element type '" + concept + "' in " + where
                                + ", which the ArchiMate metamodel does not declare");
            }
        }

        private static void requireRelationshipType(Metamodel metamodel, String concept, String where) {
            if (metamodel.relationshipType(concept).isEmpty()) {
                throw new IllegalStateException(
                        "C4 mapping references relationship type '" + concept + "' in " + where
                                + ", which the ArchiMate metamodel does not declare");
            }
        }
    }
}
