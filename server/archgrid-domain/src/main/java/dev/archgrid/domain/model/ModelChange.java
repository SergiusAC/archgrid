package dev.archgrid.domain.model;

import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipVerdict;
import dev.archgrid.domain.profile.ProfileCatalog;
import dev.archgrid.domain.shared.Id;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Applies commands to a snapshot and validates the result.
 *
 * <p>The middle of the pipeline in ADR-0004: authorisation and the optimistic check happen
 * before it, persistence after, and neither belongs here — this class has no idea a database
 * exists, which is why the rules that decide whether a model is correct can be tested without
 * one.
 *
 * <p>Commands are applied to a copy. A command that cannot apply records a violation and is
 * skipped rather than aborting the batch, so the caller learns everything wrong with their
 * change at once instead of one problem per round trip.
 */
public final class ModelChange {

    private ModelChange() {}

    /**
     * @param graph the candidate state. Meaningful only when {@link #isSuccess()}; a rejected
     *     change must be discarded whole, never partially applied.
     */
    public record Result(ModelGraph graph, List<Violation> violations) {

        public Result {
            Objects.requireNonNull(graph, "graph must not be null");
            violations = List.copyOf(violations);
        }

        public boolean isSuccess() {
            return violations.isEmpty();
        }
    }

    public static Result apply(
            ModelGraph base, List<ModelCommand> commands, Metamodel metamodel, ProfileCatalog profiles) {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(commands, "commands must not be null");
        Objects.requireNonNull(metamodel, "metamodel must not be null");
        Objects.requireNonNull(profiles, "profiles must not be null");

        ModelGraph candidate = base.copy();
        List<Violation> violations = new ArrayList<>();

        for (ModelCommand command : commands) {
            applyOne(candidate, command, metamodel, profiles, violations);
        }
        validate(candidate, metamodel, violations);

        return new Result(candidate, violations);
    }

    /**
     * @param discarded what the import threw away, in the same terms the strict pipeline uses to
     *     refuse: code, culprit, explanation. Part of the result, not a log (ADR-0015).
     */
    public record ImportResult(ModelGraph graph, List<Violation> discarded) {

        public ImportResult {
            Objects.requireNonNull(graph, "graph must not be null");
            discarded = List.copyOf(discarded);
        }
    }

    /**
     * Applies commands the way an import must: keep everything the language permits, discard what
     * it forbids, and name every discard (ADR-0015).
     *
     * <p>For the importer only — {@code POST /commands} stays strict. An imported file almost
     * always contains something somebody else's editor let through, and a pipeline that rejects
     * the whole batch over one impermissible line closes migration rather than opening it. The
     * precedent is migration V10, which faced the same choice with containment ArchiMate forbids
     * and made the same call.
     *
     * <p>The returned graph is valid without qualification, by construction: whole-graph
     * violations are cut out of the candidate — an impermissible or dangling relationship is
     * removed, a containment cycle is broken, a ghost view member is taken off, a second holder of
     * a name or of a relationship triple goes — and cutting rows cannot create new violations,
     * since validation checks what exists, never what is absent. So the loop converges, and a
     * final validation that still complains is a programming error, not a data problem.
     *
     * <p>The importer is expected to have merged duplicates by name and by triple before it gets
     * here, so that what a file says twice becomes one object with its lines intact (docs/09).
     * The cuts for those two codes are the safety net behind that, not the mechanism.
     */
    public static ImportResult applyForImport(
            ModelGraph base, List<ModelCommand> commands, Metamodel metamodel, ProfileCatalog profiles) {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(commands, "commands must not be null");
        Objects.requireNonNull(metamodel, "metamodel must not be null");
        Objects.requireNonNull(profiles, "profiles must not be null");

        ModelGraph candidate = base.copy();
        List<Violation> discarded = new ArrayList<>();

        // Per-command refusals already skip the command; here that is exactly the behaviour asked
        // for, and the violation is the report entry.
        for (ModelCommand command : commands) {
            applyOne(candidate, command, metamodel, profiles, discarded);
        }

        while (true) {
            List<Violation> found = new ArrayList<>();
            validate(candidate, metamodel, found);
            if (found.isEmpty()) {
                return new ImportResult(candidate, discarded);
            }
            for (Violation violation : found) {
                if (!cutOut(candidate, base, violation)) {
                    // A violation this method does not know how to cut is a rule added to
                    // validate() without a matching branch here — a defect to fix, not a file to
                    // tolerate. Failing loudly beats looping forever or returning an invalid graph.
                    throw new IllegalStateException(
                            "import tolerance cannot resolve " + violation.code() + " on '"
                                    + violation.subject() + "': " + violation.detail());
                }
            }
            discarded.addAll(found);
        }
    }

    /**
     * Removes the culprit a whole-graph violation names. True when something was cut.
     *
     * @param base what the repository held before this import — so that, where the choice of what
     *     to cut is free, what the file brought goes before what the repository already had
     */
    private static boolean cutOut(ModelGraph graph, ModelGraph base, Violation violation) {
        Id subject = Id.parse(violation.subject());

        if (violation.code().equals(Violation.Code.RELATIONSHIP_NOT_PERMITTED.code())
                || violation.code().equals(Violation.Code.DUPLICATE_RELATIONSHIP.code())) {
            graph.removeRelationship(subject);
            return true;
        }
        if (violation.code().equals(Violation.Code.DUPLICATE_NAME.code())) {
            // The later holder of the name is the one named; the first keeps it. An element goes
            // with everything that touched it, exactly as a deletion would take it.
            if (graph.containsElement(subject)) {
                removeElementEverywhere(graph, subject);
                return true;
            }
            if (graph.containsView(subject)) {
                graph.removeView(subject);
                return true;
            }
            return false;
        }
        if (violation.code().equals(Violation.Code.DANGLING_ENDPOINT.code())) {
            if (graph.relationship(subject).isPresent()) {
                graph.removeRelationship(subject);
                return true;
            }
            // The other shape of the same code: a view showing an element the model lost.
            Optional<View> view = graph.view(subject);
            if (view.isPresent()) {
                View pruned = view.get();
                for (Id member : view.get().elements()) {
                    if (!graph.containsElement(member)) {
                        pruned = pruned.without(member);
                    }
                }
                graph.put(pruned);
                return true;
            }
            return false;
        }
        if (violation.code().equals(Violation.Code.CONTAINMENT_CYCLE.code())) {
            // Removing any one edge of the cycle breaks it, and which one is arbitrary as far as
            // the language is concerned — the cycle is the impossible claim, not a particular
            // edge. It is not arbitrary for the repository: an edge the file brought goes before
            // one the repository already held, so that importing the same file twice cuts the
            // same edge twice instead of swapping it for a neighbour each time.
            Optional<Relationship> edge = cycleEdgeToCut(graph, base, subject);
            if (edge.isPresent()) {
                graph.removeRelationship(edge.get().key());
                return true;
            }
            return false;
        }
        return false;
    }

    /** An edge of a Composition cycle through {@code subject}: a newly imported one if there is any. */
    private static Optional<Relationship> cycleEdgeToCut(ModelGraph graph, ModelGraph base, Id subject) {
        List<Relationship> cycle = compositionCycle(graph, subject, subject, new java.util.ArrayDeque<>(), new LinkedHashSet<>());
        if (cycle == null) {
            // Reported as a cycle, yet none found from here — an earlier cut in this pass already
            // broke it. Nothing to do, and saying so is the honest answer.
            return graph.relationships().stream()
                    .filter(relationship -> "Composition".equals(relationship.conceptType())
                            && subject.equals(relationship.target()))
                    .findFirst();
        }
        return cycle.stream()
                .filter(relationship -> !base.containsRelationship(relationship.key()))
                .findFirst()
                .or(() -> Optional.of(cycle.get(cycle.size() - 1)));
    }

    /** Depth-first along Composition edges from {@code at}, back to {@code start}; the path found, or null. */
    private static List<Relationship> compositionCycle(
            ModelGraph graph, Id start, Id at, java.util.Deque<Relationship> path, Set<Id> visited) {
        for (Relationship relationship : graph.relationships()) {
            if (!"Composition".equals(relationship.conceptType()) || !relationship.source().equals(at)) {
                continue;
            }
            if (relationship.target().equals(start)) {
                path.addLast(relationship);
                List<Relationship> found = new ArrayList<>(path);
                path.removeLast();
                return found;
            }
            if (visited.add(relationship.target())) {
                path.addLast(relationship);
                List<Relationship> found = compositionCycle(graph, start, relationship.target(), path, visited);
                path.removeLast();
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ command application

    private static void applyOne(
            ModelGraph graph,
            ModelCommand command,
            Metamodel metamodel,
            ProfileCatalog profiles,
            List<Violation> violations) {
        switch (command) {
            case ModelCommand.CreateElement create -> createElement(graph, create, profiles, violations);
            case ModelCommand.RenameElement rename -> mutateElement(
                    graph, rename.key(), violations, element -> element.withName(rename.name()));
            case ModelCommand.SetElementDocumentation document -> mutateElement(
                    graph, document.key(), violations, element -> element.withDocumentation(document.documentation()));
            case ModelCommand.SetElementProperty property -> mutateElement(
                    graph,
                    property.key(),
                    violations,
                    element -> element.withProperty(property.property(), property.value()));
            case ModelCommand.DeleteElement delete -> deleteElement(graph, delete, violations);
            case ModelCommand.AddRelationship add -> addRelationship(graph, add, metamodel, violations);
            case ModelCommand.SetRelationshipProperty property -> mutateRelationship(
                    graph,
                    property.key(),
                    violations,
                    relationship -> relationship.withProperty(property.property(), property.value()));
            case ModelCommand.RenameRelationship rename -> mutateRelationship(
                    graph, rename.key(), violations, relationship -> relationship.withName(rename.name()));
            case ModelCommand.DeleteRelationship delete -> {
                if (!graph.containsRelationship(delete.key())) {
                    violations.add(Violation.of(
                            Violation.Code.UNKNOWN_SUBJECT,
                            delete.key(),
                            "There is no relationship '" + delete.key() + "' in this model."));
                } else {
                    graph.removeRelationship(delete.key());
                }
            }
            case ModelCommand.CreateView create -> createView(graph, create, metamodel, violations);
            case ModelCommand.RenameView rename -> mutateView(
                    graph, rename.key(), violations, view -> view.withName(rename.name()));
            case ModelCommand.SetViewpoint set -> {
                if (metamodel.viewpoint(set.viewpoint()).isEmpty()) {
                    violations.add(Violation.of(
                            Violation.Code.UNKNOWN_TYPE,
                            set.key(),
                            "'" + set.viewpoint() + "' is not a viewpoint in this metamodel."));
                } else {
                    mutateView(graph, set.key(), violations, view -> view.withViewpoint(set.viewpoint()));
                }
            }
            case ModelCommand.SetViewFilters set -> setViewFilters(graph, metamodel, set, violations);
            case ModelCommand.DeleteView delete -> {
                if (!graph.containsView(delete.key())) {
                    violations.add(Violation.of(
                            Violation.Code.UNKNOWN_SUBJECT,
                            delete.key(),
                            "There is no view '" + delete.key() + "' in this model."));
                } else {
                    // Only the diagram goes. Its elements belong to the repository and stay there.
                    graph.removeView(delete.key());
                }
            }
            case ModelCommand.AddToView add -> {
                if (!graph.containsElement(add.element())) {
                    violations.add(Violation.of(
                            Violation.Code.UNKNOWN_SUBJECT,
                            add.view(),
                            "There is no element '" + add.element() + "' to put on this view."));
                } else {
                    mutateView(graph, add.view(), violations, view -> view.with(add.element()));
                }
            }
            case ModelCommand.RemoveFromView remove -> mutateView(
                    graph, remove.view(), violations, view -> view.without(remove.element()));
        }
    }

    private static void createView(
            ModelGraph graph, ModelCommand.CreateView create, Metamodel metamodel, List<Violation> violations) {
        if (graph.containsView(create.key())) {
            violations.add(Violation.of(
                    Violation.Code.DUPLICATE_KEY,
                    create.key(),
                    "A view with id '" + create.key() + "' already exists in this model."));
            return;
        }
        if (metamodel.viewpoint(create.viewpoint()).isEmpty()) {
            violations.add(Violation.of(
                    Violation.Code.UNKNOWN_TYPE,
                    create.key(),
                    "'" + create.viewpoint() + "' is not a viewpoint in this metamodel."));
            return;
        }
        graph.put(View.of(create.key(), create.name(), create.viewpoint()));
    }

    /**
     * Applies a view's filters, having checked that each one can ever match.
     *
     * <p>A layer that does not exist is not a filter that shows nothing; it is a typo, and a view
     * that silently went blank because of one would send the architect looking for a deleted
     * element. The metamodel knows the layers, so this is a question it can answer.
     */
    private static void setViewFilters(
            ModelGraph graph, Metamodel metamodel, ModelCommand.SetViewFilters set, List<Violation> violations) {
        Set<String> layers = metamodel.elementTypes().stream()
                .map(type -> type.layer().code())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (ViewFilter filter : set.filters()) {
            if (filter.kind() == ViewFilter.Kind.LAYER && !layers.contains(filter.value())) {
                violations.add(Violation.of(
                        Violation.Code.UNKNOWN_TYPE,
                        set.key(),
                        "'" + filter.value() + "' is not a layer in this metamodel. Layers: " + layers + "."));
                return;
            }
        }
        mutateView(graph, set.key(), violations, view -> view.withFilters(new LinkedHashSet<>(set.filters())));
    }

    private static void mutateRelationship(
            ModelGraph graph,
            Id key,
            List<Violation> violations,
            java.util.function.UnaryOperator<Relationship> mutation) {
        Optional<Relationship> relationship = graph.relationship(key);
        if (relationship.isEmpty()) {
            violations.add(Violation.of(
                    Violation.Code.UNKNOWN_SUBJECT, key, "There is no relationship '" + key + "' in this model."));
            return;
        }
        try {
            graph.put(mutation.apply(relationship.get()));
        } catch (IllegalArgumentException ex) {
            violations.add(Violation.of(Violation.Code.NOT_APPLICABLE, key, ex.getMessage()));
        }
    }

    private static void mutateView(
            ModelGraph graph, Id key, List<Violation> violations, java.util.function.UnaryOperator<View> mutation) {
        Optional<View> view = graph.view(key);
        if (view.isEmpty()) {
            violations.add(
                    Violation.of(Violation.Code.UNKNOWN_SUBJECT, key, "There is no view '" + key + "' in this model."));
            return;
        }
        try {
            graph.put(mutation.apply(view.get()));
        } catch (IllegalArgumentException ex) {
            violations.add(Violation.of(Violation.Code.NOT_APPLICABLE, key, ex.getMessage()));
        }
    }

    private static void createElement(
            ModelGraph graph, ModelCommand.CreateElement create, ProfileCatalog profiles, List<Violation> violations) {
        if (graph.containsElement(create.key())) {
            violations.add(Violation.of(
                    Violation.Code.DUPLICATE_KEY,
                    create.key(),
                    "An element with id '" + create.key() + "' already exists in this model."));
            return;
        }
        Optional<ProfileCatalog.ResolvedType> resolved = profiles.resolve(create.type());
        if (resolved.isEmpty()) {
            violations.add(Violation.of(
                    Violation.Code.UNKNOWN_TYPE,
                    create.key(),
                    "'" + create.type() + "' is neither an ArchiMate element type nor a profile defined in this model."));
            return;
        }
        ProfileCatalog.ResolvedType type = resolved.get();
        graph.put(new Element(
                create.key(),
                type.base().concept(),
                type.profile().map(profile -> profile.name()).orElse(null),
                create.name(),
                create.documentation(),
                java.util.Map.of()));
    }

    private static void mutateElement(
            ModelGraph graph,
            Id key,
            List<Violation> violations,
            java.util.function.UnaryOperator<Element> mutation) {
        Optional<Element> element = graph.element(key);
        if (element.isEmpty()) {
            violations.add(Violation.of(
                    Violation.Code.UNKNOWN_SUBJECT, key, "There is no element '" + key + "' in this model."));
            return;
        }
        try {
            graph.put(mutation.apply(element.get()));
        } catch (IllegalArgumentException ex) {
            violations.add(Violation.of(Violation.Code.NOT_APPLICABLE, key, ex.getMessage()));
        }
    }

    /**
     * Deletes an element and every relationship touching it.
     *
     * <p>Its contents are not deleted with it, and that follows from containment being a
     * relationship rather than ownership (ADR-0011): the Composition goes, because a
     * relationship cannot survive the loss of an endpoint, and the elements it held stay in
     * the repository as elements in their own right. Deleting a subtree because the user
     * deleted its root is the kind of "helpful" behaviour that costs a morning's work.
     */
    private static void deleteElement(
            ModelGraph graph, ModelCommand.DeleteElement delete, List<Violation> violations) {
        if (!graph.containsElement(delete.key())) {
            violations.add(Violation.of(
                    Violation.Code.UNKNOWN_SUBJECT,
                    delete.key(),
                    "There is no element '" + delete.key() + "' in this model."));
            return;
        }
        removeElementEverywhere(graph, delete.key());
    }

    /** The element, its relationships, and its place on every diagram — what a deletion takes. */
    private static void removeElementEverywhere(ModelGraph graph, Id key) {
        graph.relationshipsTouching(key).forEach(relationship -> graph.removeRelationship(relationship.key()));
        // It also has to come off every diagram that shows it. A view referring to an element
        // the repository no longer has is not a state the user can fix from the diagram.
        graph.viewsShowing(key).forEach(view -> graph.put(view.without(key)));
        graph.removeElement(key);
    }

    private static void addRelationship(
            ModelGraph graph, ModelCommand.AddRelationship add, Metamodel metamodel, List<Violation> violations) {
        if (graph.containsRelationship(add.key())) {
            violations.add(Violation.of(
                    Violation.Code.DUPLICATE_KEY,
                    add.key(),
                    "A relationship with id '" + add.key() + "' already exists in this model."));
            return;
        }
        if (metamodel.relationshipType(add.type()).isEmpty()) {
            violations.add(Violation.of(
                    Violation.Code.UNKNOWN_TYPE,
                    add.key(),
                    "'" + add.type() + "' is not an ArchiMate relationship type."));
            return;
        }
        for (Id endpoint : List.of(add.source(), add.target())) {
            if (!graph.containsElement(endpoint)) {
                violations.add(Violation.of(
                        Violation.Code.DANGLING_ENDPOINT,
                        add.key(),
                        "Relationship '" + add.key() + "' refers to element '" + endpoint
                                + "', which does not exist in this model."));
                return;
            }
        }
        graph.put(new Relationship(
                add.key(), add.type(), add.source(), add.target(), add.name(), java.util.Map.of()));
    }

    // ------------------------------------------------------------------ whole-graph validation

    /**
     * Checks the invariants that no single command can guarantee on its own.
     *
     * <p>Run over the finished candidate rather than after each command, because a batch is
     * allowed to pass through intermediate states that would not be valid on their own — moving
     * two elements past each other, or swapping two names, for instance.
     */
    private static void validate(ModelGraph graph, Metamodel metamodel, List<Violation> violations) {
        detectDuplicateNames(graph, violations);
        detectDuplicateRelationships(graph, violations);
        detectContainmentCycles(graph, violations);

        for (View view : graph.views()) {
            for (Id member : view.elements()) {
                if (!graph.containsElement(member)) {
                    violations.add(Violation.of(
                            Violation.Code.DANGLING_ENDPOINT,
                            view.key(),
                            "View '" + view.key() + "' shows element '" + member
                                    + "', which is not in the repository."));
                }
            }
        }

        for (Relationship relationship : graph.relationships()) {
            Optional<Element> source = graph.element(relationship.source());
            Optional<Element> target = graph.element(relationship.target());
            if (source.isEmpty() || target.isEmpty()) {
                violations.add(Violation.of(
                        Violation.Code.DANGLING_ENDPOINT,
                        relationship.key(),
                        "Relationship '" + relationship.key() + "' has an endpoint that is not in the model."));
                continue;
            }
            RelationshipVerdict verdict = metamodel.check(
                    source.get().conceptType(), target.get().conceptType(), relationship.conceptType());
            if (!verdict.isPermitted()) {
                violations.add(Violation.of(
                        Violation.Code.RELATIONSHIP_NOT_PERMITTED, relationship.key(), explain(verdict, source.get(), target.get())));
            }
        }
    }

    /**
     * Adds the element names to the metamodel's explanation.
     *
     * <p>The metamodel can only talk about types. "Serving is not permitted from Application
     * Component to Data Object" is true but abstract; naming the two elements the architect
     * just connected is what makes it actionable.
     */
    private static String explain(RelationshipVerdict verdict, Element source, Element target) {
        return verdict.explain() + " Attempted between '" + source.name() + "' and '" + target.name() + "'.";
    }

    /**
     * Every element name, and every view name, is held by exactly one object in a repository.
     *
     * <p>Compared the one way {@link ModelGraph#nameKey} defines. The <em>later</em> holder is the
     * one reported — in a graph whose order is the order things were created, that is the newcomer,
     * and the violation tells it which existing object already has the name, which is what the
     * architect needs in order to choose: rename this, or go and use that.
     */
    private static void detectDuplicateNames(ModelGraph graph, List<Violation> violations) {
        Map<String, Element> elementsByName = new LinkedHashMap<>();
        for (Element element : graph.elements()) {
            Element first = elementsByName.putIfAbsent(ModelGraph.nameKey(element.name()), element);
            if (first != null) {
                violations.add(Violation.of(
                        Violation.Code.DUPLICATE_NAME,
                        element.key(),
                        "'" + element.name() + "' is already the name of the " + first.displayType() + " '"
                                + first.key() + "'. Element names are unique in a repository: rename one of "
                                + "them, or use the existing element instead of creating another."));
            }
        }
        Map<String, View> viewsByName = new LinkedHashMap<>();
        for (View view : graph.views()) {
            View first = viewsByName.putIfAbsent(ModelGraph.nameKey(view.name()), view);
            if (first != null) {
                violations.add(Violation.of(
                        Violation.Code.DUPLICATE_NAME,
                        view.key(),
                        "A view named '" + view.name() + "' already exists ('" + first.key()
                                + "'). View names are unique in a repository: choose another name."));
            }
        }
    }

    /**
     * One relationship of a type between two elements.
     *
     * <p>ArchiMate allows several — two Flows between the same components, one per kind of
     * traffic — and this repository deliberately does not (docs/09): a second identical edge is
     * nearly always the same statement made twice, and where it is not, the difference belongs in
     * the name or the properties of the one relationship. The later one is reported, naming the
     * earlier, so the architect knows which to annotate.
     */
    private static void detectDuplicateRelationships(ModelGraph graph, List<Violation> violations) {
        Map<String, Relationship> byTriple = new LinkedHashMap<>();
        for (Relationship relationship : graph.relationships()) {
            Relationship first = byTriple.putIfAbsent(relationship.triple(), relationship);
            if (first != null) {
                String source = graph.element(relationship.source())
                        .map(Element::name)
                        .orElseGet(() -> relationship.source().toString());
                String target = graph.element(relationship.target())
                        .map(Element::name)
                        .orElseGet(() -> relationship.target().toString());
                violations.add(Violation.of(
                        Violation.Code.DUPLICATE_RELATIONSHIP,
                        relationship.key(),
                        relationship.conceptType() + " from '" + source + "' to '" + target
                                + "' is already stated by relationship '" + first.key()
                                + "'. One relationship of a type between two elements: name or annotate "
                                + "the existing one instead of adding another."));
            }
        }
    }

    /**
     * No element may contain itself, directly or through a chain of Compositions.
     *
     * <p>The rule outlived the field it used to be checked on. Composition is ownership — a part
     * belongs to exactly one whole — so a cycle is not an unusual model but an impossible one,
     * and it would send derivation and any future C4 depth walk round forever.
     */
    private static void detectContainmentCycles(ModelGraph graph, List<Violation> violations) {
        Map<Id, List<Id>> contains = new LinkedHashMap<>();
        for (Relationship relationship : graph.relationships()) {
            if ("Composition".equals(relationship.conceptType())) {
                contains.computeIfAbsent(relationship.source(), key -> new ArrayList<>())
                        .add(relationship.target());
            }
        }

        Set<Id> settled = new LinkedHashSet<>();
        Set<Id> reported = new LinkedHashSet<>();
        for (Id start : contains.keySet()) {
            walkContainment(start, contains, settled, new LinkedHashSet<>(), reported, violations);
        }
    }

    /** Depth-first with the current path in hand, so a back edge is a cycle and is named as one. */
    private static void walkContainment(
            Id at,
            Map<Id, List<Id>> contains,
            Set<Id> settled,
            Set<Id> path,
            Set<Id> reported,
            List<Violation> violations) {
        if (settled.contains(at)) {
            return;
        }
        if (!path.add(at)) {
            if (reported.add(at)) {
                violations.add(Violation.of(
                        Violation.Code.CONTAINMENT_CYCLE,
                        at,
                        "Composition forms a cycle through '" + at + "'; an element cannot contain itself, "
                                + "directly or indirectly."));
            }
            return;
        }
        for (Id inside : contains.getOrDefault(at, List.of())) {
            walkContainment(inside, contains, settled, path, reported, violations);
        }
        path.remove(at);
        settled.add(at);
    }
}
