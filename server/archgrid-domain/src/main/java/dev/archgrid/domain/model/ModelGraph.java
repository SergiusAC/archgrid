package dev.archgrid.domain.model;

import dev.archgrid.domain.shared.Slug;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The whole semantic model of one model at one commit, held in memory.
 *
 * <p>Keeping the graph in memory is a deliberate consequence of ADR-0001: an enterprise
 * landscape is thousands of elements, not millions, so a command is applied and validated
 * against a complete picture rather than against whatever a query happened to return. Every
 * rule that spans more than one element — containment cycles, dangling endpoints — becomes a
 * plain traversal instead of a recursive query.
 *
 * <p>Mutable, and meant to be: a request loads a snapshot, applies commands to it, validates,
 * and either persists or throws the whole thing away. It is not shared between requests.
 */
public final class ModelGraph {

    private final Map<Slug, Element> elements = new LinkedHashMap<>();
    private final Map<Slug, Relationship> relationships = new LinkedHashMap<>();
    private final Map<Slug, View> views = new LinkedHashMap<>();

    public static ModelGraph empty() {
        return new ModelGraph();
    }

    public static ModelGraph of(Collection<Element> elements, Collection<Relationship> relationships) {
        return of(elements, relationships, List.of());
    }

    public static ModelGraph of(
            Collection<Element> elements, Collection<Relationship> relationships, Collection<View> views) {
        ModelGraph graph = new ModelGraph();
        elements.forEach(graph::put);
        relationships.forEach(graph::put);
        views.forEach(graph::put);
        return graph;
    }

    // ------------------------------------------------------------------ queries

    public Optional<Element> element(Slug key) {
        return Optional.ofNullable(elements.get(key));
    }

    public Optional<Relationship> relationship(Slug key) {
        return Optional.ofNullable(relationships.get(key));
    }

    public Collection<Element> elements() {
        return Collections.unmodifiableCollection(elements.values());
    }

    public Collection<Relationship> relationships() {
        return Collections.unmodifiableCollection(relationships.values());
    }

    public boolean containsElement(Slug key) {
        return elements.containsKey(key);
    }

    public boolean containsRelationship(Slug key) {
        return relationships.containsKey(key);
    }

    public Optional<View> view(Slug key) {
        return Optional.ofNullable(views.get(key));
    }

    public Collection<View> views() {
        return Collections.unmodifiableCollection(views.values());
    }

    public boolean containsView(Slug key) {
        return views.containsKey(key);
    }

    /** Which views show this element — the other half of "where is this used". */
    public List<View> viewsShowing(Slug elementKey) {
        return views.values().stream().filter(view -> view.contains(elementKey)).toList();
    }

    /**
     * The relationships a view displays: those with both endpoints on it.
     *
     * <p>Derived rather than stored, so a view can never hold a relationship whose endpoint it
     * no longer shows.
     */
    public List<Relationship> relationshipsOn(View view) {
        return relationships.values().stream()
                .filter(relationship ->
                        view.contains(relationship.source()) && view.contains(relationship.target()))
                .toList();
    }

    /**
     * What this element contains, read from Composition relationships (ADR-0011).
     *
     * <p>Not from a field: containment is a relationship, so "what is inside this" is a question
     * about the graph like any other, and it can be answered for aggregation or assignment by
     * changing one string rather than by adding a second mechanism.
     */
    public List<Element> childrenOf(Slug key) {
        return relationships.values().stream()
                .filter(relationship -> "Composition".equals(relationship.conceptType()))
                .filter(relationship -> key.equals(relationship.source()))
                .map(relationship -> elements.get(relationship.target()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Every relationship with this element at either end — what "where is this used" needs. */
    public List<Relationship> relationshipsTouching(Slug key) {
        return relationships.values().stream().filter(relationship -> relationship.touches(key)).toList();
    }

    /**
     * The containment chain from this element up to its root, nearest ancestor first.
     *
     * <p>Follows Composition relationships. Stops if it ever revisits an element, so a graph that
     * has somehow acquired a cycle produces a finite answer instead of hanging. The validator
     * reports the cycle separately; a query is not the place to throw.
     */
    public List<Element> ancestorsOf(Slug key) {
        List<Element> ancestors = new ArrayList<>();
        List<Slug> seen = new ArrayList<>();
        seen.add(key);
        Optional<Element> current = containerOf(key);
        while (current.isPresent() && !seen.contains(current.get().key())) {
            seen.add(current.get().key());
            ancestors.add(current.get());
            current = containerOf(current.get().key());
        }
        return ancestors;
    }

    /**
     * What contains this element, if anything.
     *
     * <p>Composition is ownership, so there should be at most one; where a model says otherwise
     * the first is taken rather than throwing, because a query is not the place to enforce it.
     */
    public Optional<Element> containerOf(Slug key) {
        return relationships.values().stream()
                .filter(relationship -> "Composition".equals(relationship.conceptType()))
                .filter(relationship -> key.equals(relationship.target()))
                .map(relationship -> elements.get(relationship.source()))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    public int size() {
        return elements.size() + relationships.size();
    }

    // ------------------------------------------------------------------ mutation

    public void put(Element element) {
        Objects.requireNonNull(element, "element must not be null");
        elements.put(element.key(), element);
    }

    public void put(Relationship relationship) {
        Objects.requireNonNull(relationship, "relationship must not be null");
        relationships.put(relationship.key(), relationship);
    }

    public void put(View view) {
        Objects.requireNonNull(view, "view must not be null");
        views.put(view.key(), view);
    }

    public void removeElement(Slug key) {
        elements.remove(key);
    }

    public void removeRelationship(Slug key) {
        relationships.remove(key);
    }

    public void removeView(Slug key) {
        views.remove(key);
    }

    /** A shallow copy, so commands can be applied to a candidate state without touching the original. */
    public ModelGraph copy() {
        return of(
                new ArrayList<>(elements.values()),
                new ArrayList<>(relationships.values()),
                new ArrayList<>(views.values()));
    }
}
