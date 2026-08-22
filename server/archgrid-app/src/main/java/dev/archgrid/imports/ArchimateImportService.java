package dev.archgrid.imports;

import dev.archgrid.domain.model.Element;
import dev.archgrid.domain.model.ModelCommand;
import dev.archgrid.domain.model.ModelGraph;
import dev.archgrid.domain.model.Relationship;
import dev.archgrid.domain.model.View;
import dev.archgrid.domain.model.Violation;
import dev.archgrid.domain.shared.Id;
import dev.archgrid.imports.ArchimateExchangeParser.ParsedConnection;
import dev.archgrid.imports.ArchimateExchangeParser.ParsedElement;
import dev.archgrid.imports.ArchimateExchangeParser.ParsedFolder;
import dev.archgrid.imports.ArchimateExchangeParser.ParsedModel;
import dev.archgrid.imports.ArchimateExchangeParser.ParsedNode;
import dev.archgrid.imports.ArchimateExchangeParser.ParsedRelationship;
import dev.archgrid.imports.ArchimateExchangeParser.ParsedView;
import dev.archgrid.model.ImportGateway;
import dev.archgrid.model.ModelCommandService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Turns an ArchiMate Model Exchange File into a commit, layouts, and a report.
 *
 * <p>The model half travels through the tolerant pipeline (ADR-0015): everything the language
 * permits is kept, everything it forbids is discarded by name, and after the commit the graph is
 * valid without qualification. The diagram half becomes placements and lines (ADR-0014) — the
 * exchange format's node maps to a placement and its connection to a line, one to one — written
 * through the layout endpoint's own service, outside the commit, exactly as if an architect had
 * arranged the diagrams by hand (ADR-0003).
 *
 * <p>Identity comes from the file (docs/09): an Archi identifier is a UUID and becomes the
 * object's id, so importing a file twice finds what the first import made instead of doubling
 * it. Merging follows from the repository's two uniqueness rules, in this order:
 *
 * <ol>
 *   <li>an object whose id the repository already has is <em>skipped</em> — the repository's
 *       version wins, the file's is not applied;
 *   <li>an element whose name and type an existing or earlier element already has is
 *       <em>merged</em> into it: not created, and every relationship and placement that named it
 *       now names the survivor. Seven "QazID"s in one file become one element, still drawn
 *       seven times;
 *   <li>an element whose name an element of a <em>different</em> type has is not the same thing
 *       and is <em>renamed</em> with a suffix — folding it would change what the model says;
 *   <li>a relationship whose type and ends are already stated is folded into the statement; its
 *       lines draw the survivor.
 * </ol>
 *
 * <p>Views merge by id and are renamed on a name clash rather than folded — two diagrams cannot
 * be one, and dropping one would lose an arrangement.
 *
 * <p>The report is part of the result, not a log: what was discarded, what was lost because we
 * have no place for it (labels, free lines, styles), what was interpreted rather than copied, and
 * what the merge did by name. A silent import looks successful right up to the first comparison
 * with the original.
 */
@Service
public class ArchimateImportService {

    private final ModelCommandService commands;
    private final ImportGateway gateway;

    ArchimateImportService(ModelCommandService commands, ImportGateway gateway) {
        this.commands = commands;
        this.gateway = gateway;
    }

    /**
     * Counts are what this import <em>added</em>, not what the repository holds afterwards.
     *
     * @param skipped objects the repository already held under the same id, by name
     * @param merged file elements folded into an element of the same name and type, with how many
     *     went into each
     * @param renamed objects given a suffix because an object of another kind already held the
     *     name, as "was → now"
     */
    public record Report(
            String modelName,
            UUID commitId,
            int elements,
            int relationships,
            int views,
            int folders,
            List<Violation> discarded,
            Map<String, Integer> lost,
            List<String> interpreted,
            List<String> skipped,
            Map<String, Integer> merged,
            List<String> renamed) {}

    public Report importFile(UUID modelId, byte[] content, String author) {
        ParsedModel parsed = ArchimateExchangeParser.parse(content);
        List<String> interpreted = new ArrayList<>();
        Map<String, Integer> lost = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        Map<String, Integer> merged = new LinkedHashMap<>();
        List<String> renamed = new ArrayList<>();
        int unidentified = 0;

        ModelGraph existing = commands.current(modelId);

        // ---------------------------------------------------------------- elements
        // What the repository already knows, indexed the way the rules compare: by name and type
        // for merging, by name alone for the clash a different type makes.
        Map<String, Id> elementByNameAndType = new LinkedHashMap<>();
        Set<String> elementNames = new LinkedHashSet<>();
        for (Element element : existing.elements()) {
            elementByNameAndType.put(nameAndType(element.name(), element.conceptType()), element.key());
            elementNames.add(ModelGraph.nameKey(element.name()));
        }

        Map<String, Id> elementOf = new LinkedHashMap<>(); // file id -> repository id
        Set<Id> createdElements = new LinkedHashSet<>();
        List<ModelCommand> batch = new ArrayList<>();
        int junctions = 0;
        int named = 0;
        for (ParsedElement element : parsed.elements()) {
            String concept = concept(element.type());
            if (!concept.equals(element.type())) {
                junctions++;
            }
            boolean nameless = element.name() == null || element.name().isBlank();
            String name = nameless ? concept : element.name().strip();

            Id id = ArchiIds.parse(element.id()).orElse(null);
            if (id == null) {
                unidentified++;
                id = Id.generate();
            } else if (existing.containsElement(id)) {
                skipped.add(existing.element(id).orElseThrow().name());
                elementOf.put(element.id(), id);
                continue;
            }

            if (nameless) {
                // A junction, usually: the file gave it no name, so a name says nothing about
                // which one it is. Never merged by name — ten nameless junctions are ten — and
                // named after the type, numbered, so that the uniqueness rule holds.
                named++;
                if (elementNames.contains(ModelGraph.nameKey(name))) {
                    name = freeName(name, elementNames);
                }
            } else {
                Id same = elementByNameAndType.get(nameAndType(name, concept));
                if (same != null) {
                    merged.merge(name + " (" + concept + ")", 1, Integer::sum);
                    elementOf.put(element.id(), same);
                    continue;
                }
            }
            if (!nameless && elementNames.contains(ModelGraph.nameKey(name))) {
                // A namesake of another kind holds the name. Before suffixing, look for the
                // suffixed twin an earlier import already made: "X (2)" of this type *is* this
                // element, seen before under an identifier that could not be kept.
                Id twin = suffixedTwin(name, concept, elementByNameAndType, elementNames);
                if (twin != null) {
                    merged.merge(name + " (" + concept + ")", 1, Integer::sum);
                    elementOf.put(element.id(), twin);
                    continue;
                }
                String was = name;
                name = freeName(name, elementNames);
                renamed.add(was + " → " + name + " (" + concept + ")");
            }

            elementOf.put(element.id(), id);
            createdElements.add(id);
            elementByNameAndType.put(nameAndType(name, concept), id);
            elementNames.add(ModelGraph.nameKey(name));
            batch.add(new ModelCommand.CreateElement(id, concept, name, element.documentation()));
            for (Map.Entry<String, String> property : element.properties().entrySet()) {
                batch.add(new ModelCommand.SetElementProperty(id, property.getKey(), property.getValue()));
            }
        }
        if (junctions > 0) {
            interpreted.add(junctions + " AndJunction/OrJunction became Junction — the 3.2 vocabulary has one "
                    + "concept where the exchange schema has two.");
        }
        if (named > 0) {
            interpreted.add(named + " elements had no name in the file and were named after their type, numbered "
                    + "— names are unique here, and a nameless junction is still its own junction.");
        }

        // ---------------------------------------------------------------- relationships
        Map<String, Id> relationshipByTriple = new LinkedHashMap<>();
        for (Relationship relationship : existing.relationships()) {
            relationshipByTriple.put(relationship.triple(), relationship.key());
        }
        Map<String, Id> relationshipOf = new LinkedHashMap<>(); // file id -> repository id
        int folded = 0;
        int annotated = 0;
        for (ParsedRelationship relationship : parsed.relationships()) {
            Id source = elementOf.get(relationship.sourceId());
            Id target = elementOf.get(relationship.targetId());
            if (source == null || target == null) {
                // An endpoint the file never defined; applyForImport would refuse it anyway, but
                // naming it here keeps the report exact.
                lost.merge("relationships with endpoints the file does not define", 1, Integer::sum);
                continue;
            }
            Id id = ArchiIds.parse(relationship.id()).orElse(null);
            if (id == null) {
                unidentified++;
                id = Id.generate();
            } else if (existing.containsRelationship(id)) {
                relationshipOf.put(relationship.id(), id);
                continue;
            }
            String triple = Relationship.triple(relationship.type(), source, target);
            Id same = relationshipByTriple.get(triple);
            if (same != null) {
                // Said already — by the repository, by an earlier line of the file, or by two lines
                // whose ends have just been merged into one pair. The lines still draw.
                folded++;
                relationshipOf.put(relationship.id(), same);
                continue;
            }
            relationshipByTriple.put(triple, id);
            relationshipOf.put(relationship.id(), id);
            batch.add(new ModelCommand.AddRelationship(id, relationship.type(), source, target, relationship.name()));
            if (!relationship.properties().isEmpty()) {
                annotated++;
                for (Map.Entry<String, String> property : relationship.properties().entrySet()) {
                    batch.add(new ModelCommand.SetRelationshipProperty(id, property.getKey(), property.getValue()));
                }
            }
        }
        if (folded > 0) {
            interpreted.add(folded + " relationships restated one already in the repository — same type, same "
                    + "two elements — and were folded into it; their lines draw the one that remains.");
        }
        if (annotated > 0) {
            interpreted.add("accessType and isDirected have no field here and were kept as relationship "
                    + "properties, along with the file's own properties, on " + annotated + " relationships.");
        }

        // ---------------------------------------------------------------- views: membership
        Set<String> viewNames = new LinkedHashSet<>();
        for (View view : existing.views()) {
            viewNames.add(ModelGraph.nameKey(view.name()));
        }
        Map<String, Id> viewOf = new LinkedHashMap<>(); // file id -> repository id
        Set<Id> createdViews = new LinkedHashSet<>();
        for (ParsedView view : parsed.views()) {
            String name = view.name() == null || view.name().isBlank() ? "Imported view" : view.name().strip();
            Id id = ArchiIds.parse(view.id()).orElse(null);
            if (id == null) {
                unidentified++;
                id = Id.generate();
            } else if (existing.containsView(id)) {
                skipped.add(existing.view(id).orElseThrow().name());
                viewOf.put(view.id(), id);
                continue;
            }
            if (viewNames.contains(ModelGraph.nameKey(name))) {
                // The same reasoning as for elements: a view already renamed "V (2)" by an earlier
                // import of this file is this view, and is skipped rather than made a third time.
                Id twin = suffixedView(name, existing, viewNames);
                if (twin != null) {
                    skipped.add(existing.view(twin).orElseThrow().name());
                    viewOf.put(view.id(), twin);
                    continue;
                }
                String was = name;
                name = freeName(name, viewNames);
                renamed.add(was + " → " + name + " (view)");
            }
            viewNames.add(ModelGraph.nameKey(name));
            viewOf.put(view.id(), id);
            createdViews.add(id);
            batch.add(new ModelCommand.CreateView(id, name, "layered"));
            Set<Id> members = new LinkedHashSet<>();
            for (ParsedNode node : view.nodes()) {
                Id element = node.elementRef() == null ? null : elementOf.get(node.elementRef());
                if (element != null) {
                    members.add(element);
                }
            }
            for (Id member : members) {
                batch.add(new ModelCommand.AddToView(id, member));
            }
        }
        if (unidentified > 0) {
            interpreted.add(unidentified + " identifiers in the file were not UUID-shaped (an older Archi, or a "
                    + "hand-edited file) and were replaced by fresh ones; a re-import cannot match those objects "
                    + "by identity.");
        }

        // ---------------------------------------------------------------- the commit
        ModelCommandService.ImportOutcome outcome = commands.importTolerantly(
                modelId, batch, author, "Imported " + (parsed.name() == null ? "an exchange file" : parsed.name()));
        ModelGraph graph = commands.current(modelId);

        // ---------------------------------------------------------------- views: arrangement
        int labels = 0;
        int containers = 0;
        int freeLines = 0;
        for (ParsedView view : parsed.views()) {
            Id viewId = viewOf.get(view.id());
            if (viewId == null || !createdViews.contains(viewId) || graph.view(viewId).isEmpty()) {
                // Skipped, or cut out by the tolerant pipeline: the arrangement belongs to a
                // diagram this import did not make.
                continue;
            }

            Map<String, ImportGateway.PlacementRow> placements = new LinkedHashMap<>();
            Map<String, UUID> placementOf = new LinkedHashMap<>();
            for (ParsedNode node : view.nodes()) {
                if ("Label".equals(node.kind())) {
                    labels++;
                    continue;
                }
                Id element = node.elementRef() == null ? null : elementOf.get(node.elementRef());
                if (element == null || !graph.containsElement(element)) {
                    if (node.elementRef() == null) {
                        containers++;
                    }
                    continue;
                }
                UUID id = UUID.randomUUID();
                placementOf.put(node.id(), id);
                placements.put(node.id(), new ImportGateway.PlacementRow(
                        id, element.value(), node.x(), node.y(), node.w(), node.h(), null));
            }
            // Parents in a second pass: a visual Container has no placement, so a node nested in
            // one keeps its grandparent — the nearest ancestor that is a real element.
            Map<String, ParsedNode> byId = new LinkedHashMap<>();
            view.nodes().forEach(node -> byId.put(node.id(), node));
            List<ImportGateway.PlacementRow> rows = new ArrayList<>();
            for (Map.Entry<String, ImportGateway.PlacementRow> entry : placements.entrySet()) {
                ParsedNode node = byId.get(entry.getKey());
                String ancestor = node.parentNodeId();
                while (ancestor != null && !placementOf.containsKey(ancestor)) {
                    ParsedNode up = byId.get(ancestor);
                    ancestor = up == null ? null : up.parentNodeId();
                }
                ImportGateway.PlacementRow row = entry.getValue();
                rows.add(new ImportGateway.PlacementRow(
                        row.id(),
                        row.element(),
                        row.x(),
                        row.y(),
                        row.width(),
                        row.height(),
                        ancestor == null ? null : placementOf.get(ancestor)));
            }
            gateway.savePlacements(modelId, viewId.value(), rows);

            // Connections become lines; what has no connection despite both ends being drawn is
            // hidden — the diagram did not show it, and neither must we (ADR-0012 §3).
            List<ImportGateway.LineRow> lines = new ArrayList<>();
            Set<Id> drawn = new LinkedHashSet<>();
            for (ParsedConnection connection : view.connections()) {
                if (connection.relationshipRef() == null) {
                    freeLines++;
                    continue;
                }
                Id relationship = relationshipOf.get(connection.relationshipRef());
                if (relationship == null || graph.relationship(relationship).isEmpty()) {
                    continue;
                }
                drawn.add(relationship);
                if ("NestingRelationship".equals(connection.kind())) {
                    // Expressed by the nesting the node hierarchy already carries; a line would
                    // say it twice (ADR-0011).
                    continue;
                }
                lines.add(new ImportGateway.LineRow(
                        UUID.randomUUID(),
                        relationship.value(),
                        placementOf.get(connection.sourceNodeId()),
                        placementOf.get(connection.targetNodeId()),
                        connection.bendpoints()));
            }
            gateway.saveLines(modelId, viewId.value(), lines);

            View onView = graph.view(viewId).orElseThrow();
            List<UUID> hidden = graph.relationshipsOn(onView).stream()
                    .map(Relationship::key)
                    .filter(key -> !drawn.contains(key))
                    .map(Id::value)
                    .toList();
            gateway.saveHidden(modelId, viewId.value(), hidden);
            if (onView.elements().isEmpty()) {
                lost.merge("views whose every element was discarded", 1, Integer::sum);
            }
        }
        if (labels > 0) {
            lost.put("text labels (we have no notes on views yet)", labels);
        }
        if (containers > 0) {
            lost.put("visual containers without an element", containers);
        }
        if (freeLines > 0) {
            lost.put("free lines that stand for no relationship", freeLines);
        }
        interpreted.add("Styles are not imported: colour carries the layer here, not taste (ADR-0012 §2).");

        // ---------------------------------------------------------------- folders
        // Only what this import created is filed: an element the repository already had, or one
        // the file's duplicate was folded into, keeps the folder it was in.
        int folderCount = 0;
        Filing filing = new Filing(elementOf, viewOf, createdElements, createdViews, graph, lost);
        for (ParsedFolder folder : parsed.folders()) {
            folderCount += file(modelId, folder, null, filing);
        }

        return new Report(
                parsed.name(),
                outcome.commitId(),
                outcome.elements(),
                outcome.relationships(),
                (int) createdViews.stream().filter(id -> graph.view(id).isPresent()).count(),
                folderCount,
                outcome.discarded(),
                lost,
                interpreted,
                skipped,
                merged,
                renamed);
    }

    private static String nameAndType(String name, String conceptType) {
        return ModelGraph.nameKey(name) + "|" + conceptType;
    }

    /**
     * The element an earlier import renamed out of this clash, if there is one.
     *
     * <p>Walks the suffixes in the order {@link #freeName} hands them out and stops at the first one
     * nobody holds: a twin beyond that point cannot exist, because the renaming would have taken the
     * free one. Idempotency for the objects whose file identifier could not be kept rests on this.
     */
    private static Id suffixedTwin(String name, String concept, Map<String, Id> byNameAndType, Set<String> taken) {
        for (int suffix = 2; ; suffix++) {
            String candidate = suffixed(name, suffix);
            if (!taken.contains(ModelGraph.nameKey(candidate))) {
                return null;
            }
            Id twin = byNameAndType.get(nameAndType(candidate, concept));
            if (twin != null) {
                return twin;
            }
        }
    }

    private static Id suffixedView(String name, ModelGraph existing, Set<String> taken) {
        for (int suffix = 2; ; suffix++) {
            String candidate = suffixed(name, suffix);
            String key = ModelGraph.nameKey(candidate);
            if (!taken.contains(key)) {
                return null;
            }
            for (View view : existing.views()) {
                if (ModelGraph.nameKey(view.name()).equals(key)) {
                    return view.key();
                }
            }
        }
    }

    private static String suffixed(String name, int suffix) {
        String tail = " (" + suffix + ")";
        String base = name.length() + tail.length() > Element.MAX_NAME_LENGTH
                ? name.substring(0, Element.MAX_NAME_LENGTH - tail.length()).stripTrailing()
                : name;
        return base + tail;
    }

    /**
     * The name with the smallest suffix nobody holds: {@code QazID (2)}, then {@code (3)}.
     *
     * <p>The base is shortened if the suffix would push it past the name limit; a file's longest
     * name is already most of the way there.
     */
    private static String freeName(String name, Set<String> taken) {
        for (int suffix = 2; ; suffix++) {
            String candidate = suffixed(name, suffix);
            if (!taken.contains(ModelGraph.nameKey(candidate))) {
                return candidate;
            }
        }
    }

    private record Filing(
            Map<String, Id> elementOf,
            Map<String, Id> viewOf,
            Set<Id> createdElements,
            Set<Id> createdViews,
            ModelGraph graph,
            Map<String, Integer> lost) {}

    /** Recreates one labelled item as a pair of folders — elements file one way, views the other. */
    private int file(UUID modelId, ParsedFolder folder, ParentFolders parents, Filing filing) {
        int created = 0;
        UUID elementsFolder = null;
        UUID viewsFolder = null;

        List<Id> elements = new ArrayList<>();
        List<Id> views = new ArrayList<>();
        int unfiled = 0;
        for (String member : folder.memberIds()) {
            Id element = filing.elementOf().get(member);
            Id view = filing.viewOf().get(member);
            if (element != null && filing.createdElements().contains(element) && filing.graph().containsElement(element)) {
                elements.add(element);
            } else if (view != null && filing.createdViews().contains(view) && filing.graph().view(view).isPresent()) {
                views.add(view);
            } else if (element == null && view == null) {
                // Relationships are filed in the exchange format; we have no folders for them.
                unfiled++;
            }
        }
        if (unfiled > 0) {
            filing.lost().merge("filed entries with no folder here (relationships, discards)", unfiled, Integer::sum);
        }

        if (!elements.isEmpty() || !folder.children().isEmpty()) {
            elementsFolder = gateway.createFolder(
                    modelId, "elements", folder.label(), parents == null ? null : parents.elements());
            created++;
            for (Id element : elements) {
                gateway.assignToFolder(modelId, "elements", element.value(), elementsFolder);
            }
        }
        if (!views.isEmpty()) {
            viewsFolder = gateway.createFolder(
                    modelId, "views", folder.label(), parents == null ? null : parents.views());
            created++;
            for (Id view : views) {
                gateway.assignToFolder(modelId, "views", view.value(), viewsFolder);
            }
        }
        for (ParsedFolder child : folder.children()) {
            created += file(modelId, child, new ParentFolders(elementsFolder, viewsFolder), filing);
        }
        return created;
    }

    private record ParentFolders(UUID elements, UUID views) {}

    /** AndJunction and OrJunction are the exchange schema's split of what 3.2 calls Junction. */
    private String concept(String type) {
        if ("AndJunction".equals(type) || "OrJunction".equals(type)) {
            return "Junction";
        }
        return type;
    }
}
