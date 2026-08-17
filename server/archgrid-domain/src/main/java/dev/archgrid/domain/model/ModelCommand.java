package dev.archgrid.domain.model;

import dev.archgrid.domain.shared.Slug;
import java.util.Objects;

/**
 * The only way the semantic model changes (ADR-0004).
 *
 * <p>Commands name elements by slug rather than by UUID, and that is a considered choice.
 * Slugs are stable across renames, so they are as safe as identifiers; they make a command log
 * readable and a Git diff of the Architecture-as-Code format meaningful; and they are what an
 * LLM can produce reliably when the AI sidecar proposes changes (ADR-0006). A command stream
 * full of UUIDs is correct and useless.
 */
public sealed interface ModelCommand {

    /** The element or relationship this command is about, for error reporting. */
    Slug subject();

    record CreateElement(Slug key, String type, String name, String documentation, Slug parent)
            implements ModelCommand {

        public CreateElement {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }

        public static CreateElement of(String key, String type, String name) {
            return new CreateElement(Slug.of(key), type, name, null, null);
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    record RenameElement(Slug key, String name) implements ModelCommand {

        public RenameElement {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    record SetElementDocumentation(Slug key, String documentation) implements ModelCommand {

        public SetElementDocumentation {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    /** A null {@code value} removes the property. */
    record SetElementProperty(Slug key, String property, String value) implements ModelCommand {

        public SetElementProperty {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(property, "property must not be null");
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    /** A null {@code parent} makes the element a root. */
    record MoveElement(Slug key, Slug parent) implements ModelCommand {

        public MoveElement {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    /**
     * Deletes an element from the model entirely.
     *
     * <p>Distinct from removing it from a view, which is not a model change at all. Every
     * relationship touching the element goes with it, and the caller is expected to have been
     * shown that first.
     */
    record DeleteElement(Slug key) implements ModelCommand {

        public DeleteElement {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    record AddRelationship(Slug key, String type, Slug source, Slug target, String name) implements ModelCommand {

        public AddRelationship {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(target, "target must not be null");
        }

        public static AddRelationship of(String key, String type, String source, String target) {
            return new AddRelationship(Slug.of(key), type, Slug.of(source), Slug.of(target), null);
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    record DeleteRelationship(Slug key) implements ModelCommand {

        public DeleteRelationship {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public Slug subject() {
            return key;
        }
    }
}
