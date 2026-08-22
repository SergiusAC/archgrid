package dev.archgrid.domain.shared;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The identity of a modelling artifact: an element, a relationship, a view.
 *
 * <p>One identity, not two. Every object used to carry a UUID for the system and a slug for
 * people, and the slug turned out to be a second identity nobody read: the editor synthesised it
 * from the name and truncated it, the importer transliterated it out of whatever language the
 * file was written in, and both paths had edge cases the database refused. Archi has exactly one
 * opaque identifier per object and never shows it; so do we now (docs/09).
 *
 * <p>The value is a UUID because that is what the schema already keyed everything by — and
 * because Archi's own {@code id-<32 hex>} is a UUID in costume, so an exchange file's identifiers
 * survive import unchanged and an export prints them back. Opaque on purpose: a name is an
 * attribute, with its own uniqueness rule, and renaming must never change what a thing is.
 */
public record Id(UUID value) implements Comparable<Id> {

    public Id {
        Objects.requireNonNull(value, "id value must not be null");
    }

    public static Id of(UUID value) {
        return new Id(value);
    }

    public static Id generate() {
        return new Id(UUID.randomUUID());
    }

    /**
     * Reads the canonical textual form, {@code 8-4-4-4-12} hex.
     *
     * @throws IllegalArgumentException with a message fit for a problem document
     */
    public static Id parse(String raw) {
        Objects.requireNonNull(raw, "id must not be null");
        try {
            return new Id(UUID.fromString(raw.strip()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "'" + raw + "' is not an identifier; expected a UUID such as 8f3a1c2e-4b5d-4e6f-9a0b-1c2d3e4f5a6b.");
        }
    }

    /**
     * A deterministic identity derived from a name.
     *
     * <p>For tests, where {@code named("kafka")} keeps a scenario readable, and for importers whose
     * source has an identifier of its own that is not UUID-shaped — the same source name then maps
     * to the same identity on every import. Never for the editor: two users naming a thing the
     * same would silently share an object.
     */
    public static Id named(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return new Id(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public int compareTo(Id other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
