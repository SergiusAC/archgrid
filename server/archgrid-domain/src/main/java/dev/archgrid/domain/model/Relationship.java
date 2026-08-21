package dev.archgrid.domain.model;

import dev.archgrid.domain.shared.Slug;
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
 * @param conceptType one of the eleven ArchiMate relationship types
 */
public record Relationship(
        Slug key, String conceptType, Slug source, Slug target, String name, Map<String, String> properties) {

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

    public static Relationship of(Slug key, String conceptType, Slug source, Slug target) {
        return new Relationship(key, conceptType, source, target, null, Map.of());
    }

    public Optional<String> label() {
        return Optional.ofNullable(name);
    }

    /** True when both ends are the same element. Legal for some types, nonsense for others. */
    public boolean isSelfReferential() {
        return source.equals(target);
    }

    public boolean touches(Slug elementKey) {
        return source.equals(elementKey) || target.equals(elementKey);
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
