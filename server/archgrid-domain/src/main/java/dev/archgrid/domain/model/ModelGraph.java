package dev.archgrid.domain.model;

import dev.archgrid.domain.shared.Id;
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

    private final Map<Id, Element> elements = new LinkedHashMap<>();
    private final Map<Id, Relationship> relationships = new LinkedHashMap<>();
    private final Map<Id, View> views = new LinkedHashMap<>();

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

    // ------------------------------------------------------------------ names

    /**
     * How names compare for uniqueness: case-insensitively, ignoring surrounding whitespace.
     *
     * <p>Element names and view names are each unique within a repository (docs/09), and this is
     * the one definition of "the same name" — the validation rule, the importer's merge and the
     * database index ({@code lower(btrim(name))}, V18) all agree on it, so nothing can slip past
     * one and be refused by another.
     */
    public static String nameKey(String name) {
        return name.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** The element carrying this name, if any — the repository-wide answer, since names are unique. */
    public Optional<Element> elementNamed(String name) {
        String wanted = nameKey(name);
        return elements.values().stream()
                .filter(element -> nameKey(element.name()).equals(wanted))
                .findFirst();
    }

    // ------------------------------------------------------------------ queries

    public Optional<Element> element(Id key) {
        return Optional.ofNullable(elements.get(key));
    }

    public Optional<Relationship> relationship(Id key) {
        return Optional.ofNullable(relationships.get(key));
    }

    public Collection<Element> elements() {
        return Collections.unmodifiableCollection(elements.values());
    }

    public Collection<Relationship> relationships() {
        return Collections.unmodifiableCollection(relationships.values());
    }

    public boolean containsElement(Id key) {
        return elements.containsKey(key);
    }

    public boolean containsRelationship(Id key) {
        return relationships.containsKey(key);
    }

    public Optional<View> view(Id key) {
        return Optional.ofNullable(views.get(key));
    }

    public Collection<View> views() {
        return Collections.unmodifiableCollection(views.values());
    }

    public boolean containsView(Id key) {
        return views.containsKey(key);
    }

    /** Which views show this element — the other half of "where is this used". */
    public List<View> viewsShowing(Id elementKey) {
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
     * The relationships between these elements and no others.
     *
     * <p>What a view shows, asked without a view: a C4 projection computes its element set and
     * needs the same answer (ADR-0007).
     */
    public List<Relationship> relationshipsAmong(java.util.Set<Id> keys) {
        return relationships.values().stream()
                .filter(relationship ->
                        keys.contains(relationship.source()) && keys.contains(relationship.target()))
                .toList();
    }

    /**
     * The two relationships that mean "part of" in ArchiMate.
     *
     * <p>Composition and aggregation, and nothing else. Assignment allocates — an actor to a role,
     * a node to an artifact — and realization says one thing brings another about; neither makes
     * the target part of the source, and treating them as containment would put a server inside
     * the application it happens to run.
     *
     * <p>The difference between the two is exclusivity, not membership: a composed part belongs to
     * one whole and dies with it, an aggregated part may be shared and outlive it. Both are inside
     * the whole when you ask what a system is made of, which is the question a C4 view asks.
     */
    private static boolean isContainment(Relationship relationship) {
        String type = relationship.conceptType();
        return "Composition".equals(type) || "Aggregation".equals(type);
    }

    /**
     * What this element contains, read from the graph (ADR-0011).
     *
     * <p>Not from a field: containment is a relationship, so "what is inside this" is a question
     * about the graph like any other.
     */
    public List<Element> childrenOf(Id key) {
        return relationships.values().stream()
                .filter(ModelGraph::isContainment)
                .filter(relationship -> key.equals(relationship.source()))
                .map(relationship -> elements.get(relationship.target()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Every relationship with this element at either end — what "where is this used" needs. */
    public List<Relationship> relationshipsTouching(Id key) {
        return relationships.values().stream().filter(relationship -> relationship.touches(key)).toList();
    }

    /**
     * The containment chain from this element up to its root, nearest ancestor first.
     *
     * <p>Follows Composition relationships. Stops if it ever revisits an element, so a graph that
     * has somehow acquired a cycle produces a finite answer instead of hanging. The validator
     * reports the cycle separately; a query is not the place to throw.
     */
    public List<Element> ancestorsOf(Id key) {
        List<Element> ancestors = new ArrayList<>();
        List<Id> seen = new ArrayList<>();
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
     * <p>Composition is exclusive, so an element has at most one owner — but aggregation is not,
     * and a shared part genuinely belongs to several wholes. This answers with the first the model
     * states, which is enough for the two callers that ask (rolling a neighbour up to its system,
     * walking to the top of a chain) and is not the place to argue with a model that shares.
     * Composition wins where both exist, because ownership is the stronger claim.
     */
    public Optional<Element> containerOf(Id key) {
        return relationships.values().stream()
                .filter(ModelGraph::isContainment)
                .filter(relationship -> key.equals(relationship.target()))
                .sorted(java.util.Comparator.comparing(
                        relationship -> "Composition".equals(relationship.conceptType()) ? 0 : 1))
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

    public void removeElement(Id key) {
        elements.remove(key);
    }

    public void removeRelationship(Id key) {
        relationships.remove(key);
    }

    public void removeView(Id key) {
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
