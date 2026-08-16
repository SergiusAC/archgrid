package dev.archgrid.domain.profile;

import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Metamodel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The profiles defined in one model, resolved against the metamodel they specialise.
 *
 * <p>Its central job is {@link #resolve(String)}: the editor, the DSL and the importer all
 * speak in type names that may be either an ArchiMate concept or a profile, and everything
 * downstream — relationship validation above all — needs the base concept. Resolving in one
 * place is what guarantees that a "Microservice" is validated exactly as an Application
 * Component, which is the invariant that makes profiles safe.
 */
public final class ProfileCatalog {

    private final Map<String, Profile> byName;
    private final Map<String, List<Profile>> byConceptType;
    private final Metamodel metamodel;

    private ProfileCatalog(Builder builder, Metamodel metamodel) {
        this.metamodel = metamodel;
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(builder.byName));

        Map<String, List<Profile>> grouped = new LinkedHashMap<>();
        builder.byName
                .values()
                .forEach(profile -> grouped
                        .computeIfAbsent(profile.conceptType(), key -> new ArrayList<>())
                        .add(profile));
        Map<String, List<Profile>> immutable = new LinkedHashMap<>();
        grouped.forEach((concept, profiles) -> immutable.put(concept, List.copyOf(profiles)));
        this.byConceptType = Collections.unmodifiableMap(immutable);
    }

    public static ProfileCatalog empty(Metamodel metamodel) {
        return builder().build(metamodel);
    }

    public Collection<Profile> all() {
        return byName.values();
    }

    public Optional<Profile> profile(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** The profiles available for an element type — the palette entries under a base concept. */
    public List<Profile> profilesFor(String conceptType) {
        return byConceptType.getOrDefault(conceptType, List.of());
    }

    /**
     * Resolves a type reference that may name either an ArchiMate concept or a profile.
     *
     * <p>Concepts are checked first, so a model cannot shadow a language type — the builder
     * refuses such a profile anyway, and this ordering makes the guarantee independent of it.
     */
    public Optional<ResolvedType> resolve(String typeRef) {
        Objects.requireNonNull(typeRef, "typeRef must not be null");
        Optional<ElementType> concept = metamodel.elementType(typeRef);
        if (concept.isPresent()) {
            return concept.map(ResolvedType::base);
        }
        Profile profile = byName.get(typeRef);
        if (profile == null) {
            return Optional.empty();
        }
        // The builder has already verified the base type exists, so this cannot be empty.
        return metamodel.elementType(profile.conceptType())
                .map(base -> ResolvedType.specialized(profile, base));
    }

    /** An element type reference after resolution, with the profile that produced it if any. */
    public record ResolvedType(ElementType base, Optional<Profile> profile) {

        public ResolvedType {
            Objects.requireNonNull(base, "base must not be null");
            Objects.requireNonNull(profile, "profile must not be null");
        }

        static ResolvedType base(ElementType base) {
            return new ResolvedType(base, Optional.empty());
        }

        static ResolvedType specialized(Profile profile, ElementType base) {
            return new ResolvedType(base, Optional.of(profile));
        }

        public boolean isSpecialized() {
            return profile.isPresent();
        }

        /** What to show the user: the profile name where there is one, the concept name otherwise. */
        public String displayType() {
            return profile.map(Profile::name).orElseGet(base::name);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<String, Profile> byName = new LinkedHashMap<>();

        private Builder() {}

        public Builder profile(Profile profile) {
            Objects.requireNonNull(profile, "profile must not be null");
            Profile existing = byName.putIfAbsent(profile.name(), profile);
            if (existing != null) {
                // Stricter than Archi, which allows one name under two concept types. A palette
                // entry and a DSL identifier have to mean one thing, so an import that carries a
                // collision must report it rather than quietly keeping one of the two.
                throw new IllegalArgumentException("profile name '" + profile.name()
                        + "' is already defined for " + existing.conceptType()
                        + "; profile names must be unique within a model");
            }
            return this;
        }

        public ProfileCatalog build(Metamodel metamodel) {
            Objects.requireNonNull(metamodel, "metamodel must not be null");

            for (Profile profile : byName.values()) {
                ElementType base = metamodel.elementType(profile.conceptType())
                        .orElseThrow(() -> new IllegalStateException("profile '" + profile.name()
                                + "' specialises '" + profile.conceptType()
                                + "', which the metamodel does not declare"));

                // Whether a concept can be specialised is not hard-coded: it is exactly whether
                // the metamodel permits a Specialization relationship from the concept to
                // itself. Deriving it from the matrix keeps this rule correct across versions of
                // the language.
                if (!metamodel.check(base.concept(), base.concept(), "Specialization").isPermitted()) {
                    throw new IllegalStateException("'" + base.concept()
                            + "' cannot be specialised: the metamodel permits no Specialization "
                            + "relationship from it to itself");
                }

                if (metamodel.elementType(profile.name()).isPresent()) {
                    throw new IllegalStateException("profile '" + profile.name()
                            + "' collides with an ArchiMate element type of the same name");
                }
                String lowered = profile.name().toLowerCase(Locale.ROOT);
                metamodel.elementTypes().stream()
                        .filter(type -> type.name().toLowerCase(Locale.ROOT).equals(lowered))
                        .findFirst()
                        .ifPresent(type -> {
                            throw new IllegalStateException("profile '" + profile.name()
                                    + "' collides with the display name of element type " + type.concept());
                        });
            }

            return new ProfileCatalog(this, metamodel);
        }
    }
}
