package dev.archgrid.domain.profile;

import dev.archgrid.domain.shared.Slug;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A specialisation of an ArchiMate element type: "Microservice" as a kind of Application
 * Component, "Kafka Topic" as a kind of System Software.
 *
 * <p>This is ArchiMate's own extension mechanism — the specification calls it Specialization
 * of Concepts, Archi calls it a Profile, the exchange format calls it a Stereotype. It is the
 * sanctioned way to let a customer extend the language <em>without</em> the product abandoning
 * conformance, which is why ArchGrid supports it rather than offering per-tenant custom
 * metamodels (ADR-0002).
 *
 * <p>The invariant that makes it safe: a profiled element is still an element of its base
 * type. "Microservice" participates in every relationship rule that Application Component
 * does, no more and no fewer. A profile adds vocabulary and attributes; it never changes what
 * is permitted.
 *
 * <p>Profiles belong to a <em>model</em>, not to the metamodel. They are content a customer
 * authors, and shipping them in the generated metamodel file would confuse a derived artefact
 * with an editorial one.
 *
 * @param name what appears in the palette and in the DSL, e.g. {@code Microservice}
 * @param conceptType the ArchiMate element type being specialised, e.g. {@code ApplicationComponent}
 */
public record Profile(String name, String conceptType, List<AttributeDefinition> attributes) {

    public static final int MAX_NAME_LENGTH = 100;

    public Profile {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(conceptType, "conceptType must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("profile name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "profile name must be at most " + MAX_NAME_LENGTH + " characters, was " + name.length());
        }

        attributes = List.copyOf(attributes);
        Set<Slug> keys = new LinkedHashSet<>();
        for (AttributeDefinition attribute : attributes) {
            if (!keys.add(attribute.key())) {
                throw new IllegalArgumentException(
                        "profile '" + name + "' declares attribute '" + attribute.key() + "' twice");
            }
        }
    }

    public static Profile of(String name, String conceptType, AttributeDefinition... attributes) {
        return new Profile(name, conceptType, List.of(attributes));
    }

    @Override
    public String toString() {
        return name + " (" + conceptType + ")";
    }
}
