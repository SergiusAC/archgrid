package dev.archgrid.domain.model;

import dev.archgrid.domain.shared.Id;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What changed between two states of the graph.
 *
 * <p>Computed by comparing two snapshots rather than by replaying commands, for the same reason
 * the repository writes commits by diffing: a log of what was <em>attempted</em> is not a log of
 * what changed. Two commands that cancel out belong in neither.
 *
 * <p>Objects are matched by identity, never by name (ADR-0016). A renamed element is one changed
 * element with a {@code name} field change, not a removal and an addition — which is the whole
 * reason identity is opaque and separate from the name.
 *
 * <p>Deliberately blind to layout. Coordinates, sizes and nesting are not versioned (ADR-0003), so
 * there is nothing here for them and never will be: dragging a box produces no commit and so can
 * appear in no diff. A reader comparing two commits is being shown what the model said; the
 * diagram they are looking at is always today's.
 */
public record GraphDiff(List<Change> changes) {

    public GraphDiff {
        changes = List.copyOf(changes);
    }

    /** Which of the three versioned kinds of object a change is about. */
    public enum Subject {
        ELEMENT,
        RELATIONSHIP,
        VIEW;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Verb {
        ADDED,
        REMOVED,
        CHANGED;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One attribute that differs, with both readings.
     *
     * <p>Set-valued attributes — a view's membership, its filters — are expressed as one entry per
     * element gained or lost, with the other side null. Rendering the whole set twice would bury a
     * one-element change under two hundred unchanged ones, and a diff should be the size of the
     * change rather than the size of the object.
     *
     * @param field the attribute's name; a property appears as {@code property:owner}
     */
    public record FieldChange(String field, String before, String after) {

        public FieldChange {
            Objects.requireNonNull(field, "field must not be null");
        }

        static FieldChange gained(String field, String value) {
            return new FieldChange(field, null, value);
        }

        static FieldChange lost(String field, String value) {
            return new FieldChange(field, value, null);
        }
    }

    /**
     * One object that appeared, disappeared or was edited.
     *
     * @param type the element's display type, the relationship's concept, the view's viewpoint —
     *     enough to tell two objects of one name apart without a second lookup
     * @param label what to call it: a name, or null for the relationships that have none
     * @param source the name of a relationship's source element, null for anything else. A name
     *     rather than an id because the reader of a diff should need no second request, and
     *     because a removed relationship's endpoints may themselves be gone by now.
     * @param fields empty for an addition or a removal — there the whole object is the change
     */
    public record Change(
            Subject subject,
            Verb verb,
            Id key,
            String type,
            String label,
            String source,
            String target,
            List<FieldChange> fields) {

        public Change {
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(verb, "verb must not be null");
            Objects.requireNonNull(key, "key must not be null");
            fields = List.copyOf(fields);
        }
    }

    public record Summary(int added, int removed, int changed) {

        public int total() {
            return added + removed + changed;
        }
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public Summary summary() {
        int added = 0;
        int removed = 0;
        int changed = 0;
        for (Change change : changes) {
            switch (change.verb()) {
                case ADDED -> added++;
                case REMOVED -> removed++;
                case CHANGED -> changed++;
            }
        }
        return new Summary(added, removed, changed);
    }

    public List<Change> of(Subject subject) {
        return changes.stream().filter(change -> change.subject() == subject).toList();
    }

    /**
     * Compares two states of one repository.
     *
     * <p>The order of the arguments is the order of time: {@code before} is the older state.
     * Reversing them yields the inverse diff, which is exactly what asking for the difference
     * between a commit and an earlier one means.
     */
    public static GraphDiff between(ModelGraph before, ModelGraph after) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");

        List<Change> changes = new ArrayList<>();
        diffElements(before, after, changes);
        diffRelationships(before, after, changes);
        diffViews(before, after, changes);
        changes.sort(ORDER);
        return new GraphDiff(changes);
    }

    private static final Comparator<Change> ORDER = Comparator.comparingInt(
                    (Change change) -> change.subject().ordinal())
            .thenComparingInt(change -> change.verb().ordinal())
            .thenComparing(change -> change.label() == null ? "" : change.label().toLowerCase(Locale.ROOT))
            .thenComparing(Change::key);

    // ------------------------------------------------------------------ elements

    private static void diffElements(ModelGraph before, ModelGraph after, List<Change> out) {
        for (Element old : before.elements()) {
            Element now = after.element(old.key()).orElse(null);
            if (now == null) {
                out.add(new Change(
                        Subject.ELEMENT,
                        Verb.REMOVED,
                        old.key(),
                        old.displayType(),
                        old.name(),
                        null,
                        null,
                        List.of()));
                continue;
            }
            List<FieldChange> fields = new ArrayList<>();
            compare(fields, "name", old.name(), now.name());
            compare(fields, "type", old.displayType(), now.displayType());
            compare(fields, "documentation", old.documentation(), now.documentation());
            compareProperties(fields, old.properties(), now.properties());
            if (!fields.isEmpty()) {
                out.add(new Change(
                        Subject.ELEMENT, Verb.CHANGED, now.key(), now.displayType(), now.name(), null, null, fields));
            }
        }
        for (Element now : after.elements()) {
            if (before.element(now.key()).isEmpty()) {
                out.add(new Change(
                        Subject.ELEMENT,
                        Verb.ADDED,
                        now.key(),
                        now.displayType(),
                        now.name(),
                        null,
                        null,
                        List.of()));
            }
        }
    }

    // ------------------------------------------------------------------ relationships

    private static void diffRelationships(ModelGraph before, ModelGraph after, List<Change> out) {
        for (Relationship old : before.relationships()) {
            Relationship now = after.relationship(old.key()).orElse(null);
            if (now == null) {
                out.add(relationshipChange(Verb.REMOVED, old, before, List.of()));
                continue;
            }
            List<FieldChange> fields = new ArrayList<>();
            compare(fields, "name", old.name(), now.name());
            compare(fields, "type", old.conceptType(), now.conceptType());
            // Endpoints compare by identity and are only *shown* by name. Comparing the names
            // would make every relationship at a renamed element read as changed, when what
            // changed is the element — the same reason identity is opaque in the first place
            // (ADR-0016). No command moves an endpoint, so this should never fire; it is here
            // because a row that says otherwise is worth seeing rather than hiding.
            endpoint(fields, "source", before, old.source(), after, now.source());
            endpoint(fields, "target", before, old.target(), after, now.target());
            compareProperties(fields, old.properties(), now.properties());
            if (!fields.isEmpty()) {
                out.add(relationshipChange(Verb.CHANGED, now, after, fields));
            }
        }
        for (Relationship now : after.relationships()) {
            if (before.relationship(now.key()).isEmpty()) {
                out.add(relationshipChange(Verb.ADDED, now, after, List.of()));
            }
        }
    }

    private static Change relationshipChange(
            Verb verb, Relationship relationship, ModelGraph graph, List<FieldChange> fields) {
        return new Change(
                Subject.RELATIONSHIP,
                verb,
                relationship.key(),
                relationship.conceptType(),
                relationship.name(),
                nameOf(graph, relationship.source()),
                nameOf(graph, relationship.target()),
                fields);
    }

    private static void endpoint(
            List<FieldChange> fields, String field, ModelGraph before, Id was, ModelGraph after, Id now) {
        if (!was.equals(now)) {
            fields.add(new FieldChange(field, nameOf(before, was), nameOf(after, now)));
        }
    }

    /**
     * The endpoint's name in the state it was read from.
     *
     * <p>Falls back to the id rather than to null: an endpoint that will not resolve is a graph
     * that has lost a reference, and a diff that hides it is how it stays lost.
     */
    private static String nameOf(ModelGraph graph, Id key) {
        return graph.element(key).map(Element::name).orElseGet(key::toString);
    }

    // ------------------------------------------------------------------ views

    private static void diffViews(ModelGraph before, ModelGraph after, List<Change> out) {
        for (View old : before.views()) {
            View now = after.view(old.key()).orElse(null);
            if (now == null) {
                out.add(new Change(
                        Subject.VIEW, Verb.REMOVED, old.key(), old.viewpoint(), old.name(), null, null, List.of()));
                continue;
            }
            List<FieldChange> fields = new ArrayList<>();
            compare(fields, "name", old.name(), now.name());
            compare(fields, "viewpoint", old.viewpoint(), now.viewpoint());
            compareMembership(fields, before, old.elements(), after, now.elements());
            compareFilters(fields, old.filters(), now.filters());
            if (!fields.isEmpty()) {
                out.add(new Change(
                        Subject.VIEW, Verb.CHANGED, now.key(), now.viewpoint(), now.name(), null, null, fields));
            }
        }
        for (View now : after.views()) {
            if (before.view(now.key()).isEmpty()) {
                out.add(new Change(
                        Subject.VIEW, Verb.ADDED, now.key(), now.viewpoint(), now.name(), null, null, List.of()));
            }
        }
    }

    /**
     * Membership, named rather than counted.
     *
     * <p>"Three elements added" is the kind of summary that sends the reader back to the diagram to
     * find out which three; the names cost nothing and answer it here.
     */
    private static void compareMembership(
            List<FieldChange> fields, ModelGraph before, Set<Id> was, ModelGraph after, Set<Id> now) {
        List<String> gained = new ArrayList<>();
        for (Id key : now) {
            if (!was.contains(key)) {
                gained.add(nameOf(after, key));
            }
        }
        List<String> lost = new ArrayList<>();
        for (Id key : was) {
            if (!now.contains(key)) {
                lost.add(nameOf(before, key));
            }
        }
        gained.sort(String.CASE_INSENSITIVE_ORDER);
        lost.sort(String.CASE_INSENSITIVE_ORDER);
        gained.forEach(name -> fields.add(FieldChange.gained("elements", name)));
        lost.forEach(name -> fields.add(FieldChange.lost("elements", name)));
    }

    private static void compareFilters(List<FieldChange> fields, Set<ViewFilter> was, Set<ViewFilter> now) {
        Set<String> before = describe(was);
        Set<String> after = describe(now);
        after.stream()
                .filter(filter -> !before.contains(filter))
                .forEach(filter -> fields.add(FieldChange.gained("filters", filter)));
        before.stream()
                .filter(filter -> !after.contains(filter))
                .forEach(filter -> fields.add(FieldChange.lost("filters", filter)));
    }

    private static Set<String> describe(Set<ViewFilter> filters) {
        Set<String> described = new LinkedHashSet<>();
        for (ViewFilter filter : filters) {
            described.add(
                    switch (filter.kind()) {
                        case LAYER -> "layer=" + filter.value();
                        // A property filter with no value asks "has this property at all", which is
                        // a different condition from one that pins a value.
                        case PROPERTY -> filter.value() == null ? filter.name() : filter.name() + "=" + filter.value();
                    });
        }
        return described;
    }

    // ------------------------------------------------------------------ attributes

    private static void compare(List<FieldChange> fields, String field, String was, String now) {
        if (!Objects.equals(was, now)) {
            fields.add(new FieldChange(field, was, now));
        }
    }

    private static void compareProperties(List<FieldChange> fields, Map<String, String> was, Map<String, String> now) {
        // Sorted, so that two runs over the same change read the same way. Property order in the
        // graph is insertion order, an accident of how the file or the editor happened to write it.
        Set<String> keys = new TreeSet<>(was.keySet());
        keys.addAll(now.keySet());
        for (String key : keys) {
            compare(fields, "property:" + key, was.get(key), now.get(key));
        }
    }
}
