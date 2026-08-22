package dev.archgrid.domain.model;

import dev.archgrid.domain.shared.Id;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A relationship between two elements of the semantic model.
 *
 * <p>Always explicit. Derived relationships — the ones ArchiMate lets you infer along a chain
 * of structural links — are computed from the graph when a view asks for them and are never
 * stored, because storing them would make the model's own history unreadable.
 *
 * <p>Type, source and target are fixed for the relationship's life; there is no command to change
 * them, and together they are what makes it unique in a repository ({@link #triple()}). The name
 * is the one attribute an architect edits, and it is optional and never unique: two Flows out of
 * one component are told apart by what they carry, not by where they go.
 *
 * @param conceptType one of the eleven ArchiMate relationship types
 */
public record Relationship(
        Id key, String conceptType, Id source, Id target, String name, Map<String, String> properties) {

    public static final int MAX_NAME_LENGTH = 200;

    public Relationship {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(conceptType, "conceptType must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        if (name != null && name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "relationship name must be at most " + MAX_NAME_LENGTH + " characters: " + key);
        }
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public static Relationship of(Id key, String conceptType, Id source, Id target) {
        return new Relationship(key, conceptType, source, target, null, Map.of());
    }

    public Optional<String> label() {
        return Optional.ofNullable(name);
    }

    /** True when both ends are the same element. Legal for some types, nonsense for others. */
    public boolean isSelfReferential() {
        return source.equals(target);
    }

    public boolean touches(Id elementKey) {
        return source.equals(elementKey) || target.equals(elementKey);
    }

    /**
     * What makes this relationship unique in a repository: its type and its two ends.
     *
     * <p>A second relationship of the same type between the same pair is refused (docs/09, a
     * deliberate step beyond what ArchiMate itself requires), so this is the key the rule and the
     * importer's merge both compare by. The database agrees, with a unique index on the same
     * three columns.
     */
    public String triple() {
        return triple(conceptType, source, target);
    }

    public static String triple(String conceptType, Id source, Id target) {
        return conceptType + "|" + source + "|" + target;
    }

    /** A null {@code newName} unnames the relationship; the type alone then labels its line. */
    public Relationship withName(String newName) {
        String trimmed = newName == null || newName.isBlank() ? null : newName.strip();
        return new Relationship(key, conceptType, source, target, trimmed, properties);
    }

    /**
     * A null {@code value} removes the property, which is how the command surface expresses
     * deletion — the same reading as on an element.
     */
    public Relationship withProperty(String property, String value) {
        Objects.requireNonNull(property, "property must not be null");
        Map<String, String> updated = new LinkedHashMap<>(properties);
        if (value == null) {
            updated.remove(property);
        } else {
            updated.put(property, value);
        }
        return new Relationship(key, conceptType, source, target, name, updated);
    }
}
