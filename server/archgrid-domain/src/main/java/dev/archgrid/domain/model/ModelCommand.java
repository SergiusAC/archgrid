package dev.archgrid.domain.model;

import dev.archgrid.domain.shared.Id;
import java.util.Objects;

/**
 * The only way the semantic model changes (ADR-0004).
 *
 * <p>Commands name objects by identity, and identity is opaque (docs/09). Readability of the
 * command log is bought elsewhere — wherever a log is shown, an id is resolved to the name it had
 * — rather than by giving every object a second, human-shaped key that renaming could never touch
 * and importing could never get right.
 *
 * <p>The field is still called {@code key} on the wire and here: it is what a command addresses
 * an object by. Only what it holds has changed.
 */
public sealed interface ModelCommand {

    /** The element, relationship or view this command is about, for error reporting. */
    Id subject();

    record CreateElement(Id key, String type, String name, String documentation) implements ModelCommand {

        public CreateElement {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }

        public static CreateElement of(Id key, String type, String name) {
            return new CreateElement(key, type, name, null);
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    record RenameElement(Id key, String name) implements ModelCommand {

        public RenameElement {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    record SetElementDocumentation(Id key, String documentation) implements ModelCommand {

        public SetElementDocumentation {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    /** A null {@code value} removes the property. */
    record SetElementProperty(Id key, String property, String value) implements ModelCommand {

        public SetElementProperty {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(property, "property must not be null");
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    /**
     * A null {@code value} removes the property.
     *
     * <p>Relationships carry properties as elements do — ArchiMate says so, the schema has held
     * the column since phase 2, and the exchange format carries them (ADR-0012). Until this
     * command existed they could be read and never written, which the import would have
     * discovered the hard way.
     */
    record SetRelationshipProperty(Id key, String property, String value) implements ModelCommand {

        public SetRelationshipProperty {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(property, "property must not be null");
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    /**
     * Names a relationship, or unnames it with a null.
     *
     * <p>A relationship's name is optional and never unique — two Flows out of one component are
     * told apart by what they carry, not by where they go — and until this command the only moment
     * to give one was creation. With the type, source and target fixed for life (they are the
     * relationship's identity as far as the uniqueness rule is concerned), the name is the one
     * thing about a relationship an architect edits.
     */
    record RenameRelationship(Id key, String name) implements ModelCommand {

        public RenameRelationship {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public Id subject() {
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
    record DeleteElement(Id key) implements ModelCommand {

        public DeleteElement {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    record AddRelationship(Id key, String type, Id source, Id target, String name) implements ModelCommand {

        public AddRelationship {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(target, "target must not be null");
        }

        public static AddRelationship of(Id key, String type, Id source, Id target) {
            return new AddRelationship(key, type, source, target, null);
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    record DeleteRelationship(Id key) implements ModelCommand {

        public DeleteRelationship {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    // ------------------------------------------------------------------ views
    //
    // View definitions are versioned like the semantic model, because "this element belongs on
    // the context diagram" is a documentation decision worth attributing (ADR-0003, ADR-0009).
    // Coordinates are not: they never travel through a command.

    record CreateView(Id key, String name, String viewpoint) implements ModelCommand {

        public CreateView {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(viewpoint, "viewpoint must not be null");
        }

        public static CreateView of(Id key, String name, String viewpoint) {
            return new CreateView(key, name, viewpoint);
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    record RenameView(Id key, String name) implements ModelCommand {

        public RenameView {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    record SetViewpoint(Id key, String viewpoint) implements ModelCommand {

        public SetViewpoint {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(viewpoint, "viewpoint must not be null");
        }

        @Override
        public Id subject() {
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
    record SetViewFilters(Id key, java.util.List<ViewFilter> filters) implements ModelCommand {

        public SetViewFilters {
            Objects.requireNonNull(key, "key must not be null");
            filters = java.util.List.copyOf(filters);
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    /** Deletes the diagram. The elements it showed are untouched — they belong to the repository. */
    record DeleteView(Id key) implements ModelCommand {

        public DeleteView {
            Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public Id subject() {
            return key;
        }
    }

    record AddToView(Id view, Id element) implements ModelCommand {

        public AddToView {
            Objects.requireNonNull(view, "view must not be null");
            Objects.requireNonNull(element, "element must not be null");
        }

        public static AddToView of(Id view, Id element) {
            return new AddToView(view, element);
        }

        @Override
        public Id subject() {
            return view;
        }
    }

    /** Takes the element off this diagram only. It stays in the repository and in other views. */
    record RemoveFromView(Id view, Id element) implements ModelCommand {

        public RemoveFromView {
            Objects.requireNonNull(view, "view must not be null");
            Objects.requireNonNull(element, "element must not be null");
        }

        @Override
        public Id subject() {
            return view;
        }
    }
}
