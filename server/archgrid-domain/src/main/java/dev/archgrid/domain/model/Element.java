package dev.archgrid.domain.model;

import dev.archgrid.domain.shared.Id;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An element of the semantic model.
 *
 * <p>Exists independently of any diagram. That is inverted from how a drawing tool works and
 * it is the invariant the whole product rests on (ADR-0003): removing an element from a view
 * and deleting it from the model are different operations, and only the second one is this
 * record disappearing.
 *
 * <p>It has no parent. What contains what is a Composition relationship like any other, and
 * drawing one element inside another is how a single view chooses to present it (ADR-0011).
 * As a field it was invisible to "where is this used", impossible to type as aggregation or
 * assignment instead, and it forced every diagram to nest the same pair the same way.
 *
 * @param conceptType always the <em>base</em> ArchiMate concept. A profiled element carries
 *     its profile in {@code profileName} and still validates as this concept, which is what
 *     keeps specialisation from widening what is permitted (ADR-0002).
 * @param profileName the specialisation applied, or null
 */
public record Element(
        Id key,
        String conceptType,
        String profileName,
        String name,
        String documentation,
        Map<String, String> properties) {

    public static final int MAX_NAME_LENGTH = 200;

    public Element {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(conceptType, "conceptType must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("element name must not be blank: " + key);
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "element name must be at most " + MAX_NAME_LENGTH + " characters: " + key);
        }
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public static Element of(Id key, String conceptType, String name) {
        return new Element(key, conceptType, null, name, null, Map.of());
    }

    public Optional<String> profile() {
        return Optional.ofNullable(profileName);
    }

    public Optional<String> documentationText() {
        return Optional.ofNullable(documentation);
    }

    /** What the user calls this type: the profile where there is one, the concept otherwise. */
    public String displayType() {
        return profileName != null ? profileName : conceptType;
    }

    public Element withName(String newName) {
        return new Element(key, conceptType, profileName, newName, documentation, properties);
    }

    public Element withDocumentation(String newDocumentation) {
        return new Element(key, conceptType, profileName, name, newDocumentation, properties);
    }

    /** A null {@code value} removes the property, which is how the command surface expresses deletion. */
    public Element withProperty(String property, String value) {
        Objects.requireNonNull(property, "property must not be null");
        Map<String, String> updated = new LinkedHashMap<>(properties);
        if (value == null) {
            updated.remove(property);
        } else {
            updated.put(property, value);
        }
        return new Element(key, conceptType, profileName, name, documentation, updated);
    }
}
