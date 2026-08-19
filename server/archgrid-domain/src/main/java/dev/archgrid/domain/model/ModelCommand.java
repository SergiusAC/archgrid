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

    record CreateElement(Slug key, String type, String name, String documentation) implements ModelCommand {

        public CreateElement {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }

        public static CreateElement of(String key, String type, String name) {
            return new CreateElement(Slug.of(key), type, name, null);
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

    // ------------------------------------------------------------------ views
    //
    // View definitions are versioned like the semantic model, because "this element belongs on
    // the context diagram" is a documentation decision worth attributing (ADR-0003, ADR-0009).
    // Coordinates are not: they never travel through a command.

    record CreateView(Slug key, String name, String viewpoint) implements ModelCommand {

        public CreateView {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(viewpoint, "viewpoint must not be null");
        }

        public static CreateView of(String key, String name, String viewpoint) {
            return new CreateView(Slug.of(key), name, viewpoint);
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    record RenameView(Slug key, String name) implements ModelCommand {

        public RenameView {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    record SetViewpoint(Slug key, String viewpoint) implements ModelCommand {

        public SetViewpoint {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(viewpoint, "viewpoint must not be null");
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    /**
     * Replaces the view's filters wholesale.
     *
     * <p>Wholesale rather than add/remove: a filter set is small, read as a whole, and edited as a
     * whole, and two commands would make "the view now shows the technology layer only" arrive in
     * the history as two half-decisions.
     */
    record SetViewFilters(Slug key, java.util.List<ViewFilter> filters) implements ModelCommand {

        public SetViewFilters {
            Objects.requireNonNull(key, "key must not be null");
            filters = java.util.List.copyOf(filters);
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    /** Deletes the diagram. The elements it showed are untouched — they belong to the repository. */
    record DeleteView(Slug key) implements ModelCommand {

        public DeleteView {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public Slug subject() {
            return key;
        }
    }

    record AddToView(Slug view, Slug element) implements ModelCommand {

        public AddToView {
            Objects.requireNonNull(view, "view must not be null");
            Objects.requireNonNull(element, "element must not be null");
        }

        public static AddToView of(String view, String element) {
            return new AddToView(Slug.of(view), Slug.of(element));
        }

        @Override
        public Slug subject() {
            return view;
        }
    }

    /** Takes the element off this diagram only. It stays in the repository and in other views. */
    record RemoveFromView(Slug view, Slug element) implements ModelCommand {

        public RemoveFromView {
            Objects.requireNonNull(view, "view must not be null");
            Objects.requireNonNull(element, "element must not be null");
        }

        @Override
        public Slug subject() {
            return view;
        }
    }
}
