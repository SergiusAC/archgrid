package dev.archgrid.domain.model;

import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipVerdict;
import dev.archgrid.domain.profile.ProfileCatalog;
import dev.archgrid.domain.shared.Slug;
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
     * removed, a containment cycle is broken, a ghost view member is taken off — and cutting rows
     * cannot create new violations, since validation checks what exists, never what is absent. So
     * the loop converges, and a final validation that still complains is a programming error, not
     * a data problem.
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
                if (!cutOut(candidate, violation)) {
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

    /** Removes the culprit a whole-graph violation names. True when something was cut. */
    private static boolean cutOut(ModelGraph graph, Violation violation) {
        Slug subject = Slug.of(violation.subject());

        if (violation.code().equals(Violation.Code.RELATIONSHIP_NOT_PERMITTED.code())) {
            graph.removeRelationship(subject);
            return true;
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
                for (Slug member : view.get().elements()) {
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
            // Every element on a cycle has an incoming Composition; removing one breaks it. Which
            // one is arbitrary, and arbitrariness is fine — the cycle itself is the impossible
            // claim, not any particular edge of it.
            for (Relationship relationship : graph.relationships()) {
                if ("Composition".equals(relationship.conceptType()) && subject.equals(relationship.target())) {
                    graph.removeRelationship(relationship.key());
                    return true;
                }
            }
            return false;
        }
        return false;
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
                    "A view with key '" + create.key() + "' already exists in this model."));
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
            Slug key,
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
            ModelGraph graph, Slug key, List<Violation> violations, java.util.function.UnaryOperator<View> mutation) {
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
                    "An element with key '" + create.key() + "' already exists in this model."));
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
            Slug key,
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
        graph.relationshipsTouching(delete.key())
                .forEach(relationship -> graph.removeRelationship(relationship.key()));
        // It also has to come off every diagram that shows it. A view referring to an element
        // the repository no longer has is not a state the user can fix from the diagram.
        graph.viewsShowing(delete.key()).forEach(view -> graph.put(view.without(delete.key())));
        graph.removeElement(delete.key());
    }

    private static void addRelationship(
            ModelGraph graph, ModelCommand.AddRelationship add, Metamodel metamodel, List<Violation> violations) {
        if (graph.containsRelationship(add.key())) {
            violations.add(Violation.of(
                    Violation.Code.DUPLICATE_KEY,
                    add.key(),
                    "A relationship with key '" + add.key() + "' already exists in this model."));
            return;
        }
        if (metamodel.relationshipType(add.type()).isEmpty()) {
            violations.add(Violation.of(
                    Violation.Code.UNKNOWN_TYPE,
                    add.key(),
                    "'" + add.type() + "' is not an ArchiMate relationship type."));
            return;
        }
        for (Slug endpoint : List.of(add.source(), add.target())) {
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
     * two elements past each other, for instance.
     */
    private static void validate(ModelGraph graph, Metamodel metamodel, List<Violation> violations) {
        detectContainmentCycles(graph, violations);

        for (View view : graph.views()) {
            for (Slug member : view.elements()) {
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
     * No element may contain itself, directly or through a chain of Compositions.
     *
     * <p>The rule outlived the field it used to be checked on. Composition is ownership — a part
     * belongs to exactly one whole — so a cycle is not an unusual model but an impossible one,
     * and it would send derivation and any future C4 depth walk round forever.
     */
    private static void detectContainmentCycles(ModelGraph graph, List<Violation> violations) {
        Map<Slug, List<Slug>> contains = new LinkedHashMap<>();
        for (Relationship relationship : graph.relationships()) {
            if ("Composition".equals(relationship.conceptType())) {
                contains.computeIfAbsent(relationship.source(), key -> new ArrayList<>())
                        .add(relationship.target());
            }
        }

        Set<Slug> settled = new LinkedHashSet<>();
        Set<Slug> reported = new LinkedHashSet<>();
        for (Slug start : contains.keySet()) {
            walkContainment(start, contains, settled, new LinkedHashSet<>(), reported, violations);
        }
    }

    /** Depth-first with the current path in hand, so a back edge is a cycle and is named as one. */
    private static void walkContainment(
            Slug at,
            Map<Slug, List<Slug>> contains,
            Set<Slug> settled,
            Set<Slug> path,
            Set<Slug> reported,
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
        for (Slug inside : contains.getOrDefault(at, List.of())) {
            walkContainment(inside, contains, settled, path, reported, violations);
        }
        path.remove(at);
        settled.add(at);
    }
}
