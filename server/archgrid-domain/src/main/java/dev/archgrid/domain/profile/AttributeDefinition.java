package dev.archgrid.domain.profile;

import dev.archgrid.domain.shared.Slug;
import java.util.Objects;

/**
 * One attribute a profile adds to the elements it specialises.
 *
 * <p>Deliberately just key, name and type. Whether an attribute is <em>mandatory</em> is a
 * governance rule about model quality, not part of a type definition, and it does not survive
 * an export to the exchange format — encoding it here would silently lose information on the
 * round trip. Such rules belong with the rest of model validation, not with the schema of a
 * specialisation.
 *
 * @param key stable identifier, used by the future Architecture-as-Code representation
 * @param name what a human sees in the properties panel
 */
public record AttributeDefinition(Slug key, String name, AttributeType type) {

    public static final int MAX_NAME_LENGTH = 100;

    public AttributeDefinition {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("attribute name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "attribute name must be at most " + MAX_NAME_LENGTH + " characters, was " + name.length());
        }
    }

    public static AttributeDefinition of(String key, String name, AttributeType type) {
        return new AttributeDefinition(Slug.of(key), name, type);
    }
}
