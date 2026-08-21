package dev.archgrid.imports;

import dev.archgrid.domain.model.ModelCommand;
import dev.archgrid.domain.model.ModelGraph;
import dev.archgrid.domain.model.Violation;
import dev.archgrid.domain.shared.Slug;
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
 * <p>The report is part of the result, not a log: what was discarded, what was lost because we
 * have no place for it (labels, free lines, styles), and what was interpreted rather than copied.
 * A silent import looks successful right up to the first comparison with the original.
 */
@Service
public class ArchimateImportService {

    private final ModelCommandService commands;
    private final ImportGateway gateway;

    ArchimateImportService(ModelCommandService commands, ImportGateway gateway) {
        this.commands = commands;
        this.gateway = gateway;
    }

    public record Report(
            String modelName,
            UUID commitId,
            int elements,
            int relationships,
            int views,
            int folders,
            List<Violation> discarded,
            Map<String, Integer> lost,
            List<String> interpreted) {}

    public Report importFile(UUID modelId, byte[] content, String author) {
        ParsedModel parsed = ArchimateExchangeParser.parse(content);
        List<String> interpreted = new ArrayList<>();
        Map<String, Integer> lost = new LinkedHashMap<>();

        // ---------------------------------------------------------------- identities
        // File ids are opaque; what we keep are slugs, generated from names the way the editor
        // generates them — transliterated, slugified, suffixed on collision — so the imported
        // command log reads like one a person produced.
        ModelGraph existing = commands.current(modelId);
        Set<String> taken = new LinkedHashSet<>();
        existing.elements().forEach(element -> taken.add(element.key().value()));
        existing.relationships().forEach(relationship -> taken.add(relationship.key().value()));
        existing.views().forEach(view -> taken.add(view.key().value()));

        Map<String, String> elementSlug = new LinkedHashMap<>();
        List<ModelCommand> batch = new ArrayList<>();
        int junctions = 0;
        for (ParsedElement element : parsed.elements()) {
            String concept = concept(element.type());
            if (!concept.equals(element.type())) {
                junctions++;
            }
            String slug = Slugs.unique(element.name(), Slugs.fallbackFor(concept), taken);
            elementSlug.put(element.id(), slug);
            String name = element.name() == null || element.name().isBlank() ? concept : element.name();
            batch.add(new ModelCommand.CreateElement(Slug.of(slug), concept, name, element.documentation()));
            element.properties()
                    .forEach((property, value) -> batch.add(
                            new ModelCommand.SetElementProperty(Slug.of(slug), property, value)));
        }
        if (junctions > 0) {
            interpreted.add(junctions + " AndJunction/OrJunction became Junction — the 3.2 vocabulary has one "
                    + "concept where the exchange schema has two.");
        }

        Map<String, String> relationshipSlug = new LinkedHashMap<>();
        int annotated = 0;
        for (ParsedRelationship relationship : parsed.relationships()) {
            String source = elementSlug.get(relationship.sourceId());
            String target = elementSlug.get(relationship.targetId());
            if (source == null || target == null) {
                // An endpoint the file never defined; applyForImport would refuse it anyway, but
                // naming it here keeps the report exact.
                lost.merge("relationships with endpoints the file does not define", 1, Integer::sum);
                continue;
            }
            String slug = Slugs.unique(
                    source + "-" + relationship.type().toLowerCase(java.util.Locale.ROOT) + "-" + target,
                    "relationship",
                    taken);
            relationshipSlug.put(relationship.id(), slug);
            batch.add(new ModelCommand.AddRelationship(
                    Slug.of(slug), relationship.type(), Slug.of(source), Slug.of(target), relationship.name()));
            if (!relationship.properties().isEmpty()) {
                annotated++;
                relationship.properties()
                        .forEach((property, value) -> batch.add(
                                new ModelCommand.SetRelationshipProperty(Slug.of(slug), property, value)));
            }
        }
        if (annotated > 0) {
            interpreted.add("accessType and isDirected have no field here and were kept as relationship "
                    + "properties, along with the file's own properties, on " + annotated + " relationships.");
        }

        // ---------------------------------------------------------------- views: membership
        Map<String, String> viewSlug = new LinkedHashMap<>();
        for (ParsedView view : parsed.views()) {
            String name = view.name() == null || view.name().isBlank() ? "Imported view" : view.name();
            String slug = Slugs.unique(name, "view", taken);
            viewSlug.put(view.id(), slug);
            batch.add(new ModelCommand.CreateView(Slug.of(slug), name, "layered"));
            Set<String> members = new LinkedHashSet<>();
            for (ParsedNode node : view.nodes()) {
                String element = node.elementRef() == null ? null : elementSlug.get(node.elementRef());
                if (element != null) {
                    members.add(element);
                }
            }
            for (String member : members) {
                batch.add(new ModelCommand.AddToView(Slug.of(slug), Slug.of(member)));
            }
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
            String slug = viewSlug.get(view.id());
            if (slug == null || graph.view(Slug.of(slug)).isEmpty()) {
                continue;
            }

            Map<String, ImportGateway.PlacementRow> placements = new LinkedHashMap<>();
            Map<String, UUID> placementOf = new LinkedHashMap<>();
            for (ParsedNode node : view.nodes()) {
                if ("Label".equals(node.kind())) {
                    labels++;
                    continue;
                }
                String element = node.elementRef() == null ? null : elementSlug.get(node.elementRef());
                if (element == null || !graph.containsElement(Slug.of(element))) {
                    if (node.elementRef() == null) {
                        containers++;
                    }
                    continue;
                }
                UUID id = UUID.randomUUID();
                placementOf.put(node.id(), id);
                placements.put(node.id(), new ImportGateway.PlacementRow(
                        id, element, node.x(), node.y(), node.w(), node.h(), null));
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
            gateway.savePlacements(modelId, slug, rows);

            // Connections become lines; what has no connection despite both ends being drawn is
            // hidden — the diagram did not show it, and neither must we (ADR-0012 §3).
            List<ImportGateway.LineRow> lines = new ArrayList<>();
            Set<String> drawnRelationships = new LinkedHashSet<>();
            for (ParsedConnection connection : view.connections()) {
                if (connection.relationshipRef() == null) {
                    freeLines++;
                    continue;
                }
                String relationship = relationshipSlug.get(connection.relationshipRef());
                if (relationship == null || graph.relationship(Slug.of(relationship)).isEmpty()) {
                    continue;
                }
                drawnRelationships.add(relationship);
                if ("NestingRelationship".equals(connection.kind())) {
                    // Expressed by the nesting the node hierarchy already carries; a line would
                    // say it twice (ADR-0011).
                    continue;
                }
                lines.add(new ImportGateway.LineRow(
                        UUID.randomUUID(),
                        relationship,
                        placementOf.get(connection.sourceNodeId()),
                        placementOf.get(connection.targetNodeId()),
                        connection.bendpoints()));
            }
            gateway.saveLines(modelId, slug, lines);

            Set<Slug> onView = graph.view(Slug.of(slug)).orElseThrow().elements();
            List<String> hidden = graph.relationshipsOn(graph.view(Slug.of(slug)).orElseThrow()).stream()
                    .map(relationship -> relationship.key().value())
                    .filter(key -> !drawnRelationships.contains(key))
                    .toList();
            gateway.saveHidden(modelId, slug, hidden);
            if (onView.isEmpty()) {
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
        int folderCount = 0;
        for (ParsedFolder folder : parsed.folders()) {
            folderCount += file(modelId, folder, null, elementSlug, viewSlug, graph, lost);
        }

        return new Report(
                parsed.name(),
                outcome.commitId(),
                outcome.elements(),
                outcome.relationships(),
                (int) parsed.views().stream().filter(view -> {
                    String slug = viewSlug.get(view.id());
                    return slug != null && graph.view(Slug.of(slug)).isPresent();
                }).count(),
                folderCount,
                outcome.discarded(),
                lost,
                interpreted);
    }

    /** Recreates one labelled item as a pair of folders — elements file one way, views the other. */
    private int file(
            UUID modelId,
            ParsedFolder folder,
            ParentFolders parents,
            Map<String, String> elementSlug,
            Map<String, String> viewSlug,
            ModelGraph graph,
            Map<String, Integer> lost) {
        int created = 0;
        UUID elementsFolder = null;
        UUID viewsFolder = null;

        List<String> elements = new ArrayList<>();
        List<String> views = new ArrayList<>();
        int unfiled = 0;
        for (String member : folder.memberIds()) {
            String element = elementSlug.get(member);
            String view = viewSlug.get(member);
            if (element != null && graph.containsElement(Slug.of(element))) {
                elements.add(element);
            } else if (view != null && graph.view(Slug.of(view)).isPresent()) {
                views.add(view);
            } else {
                // Relationships are filed in the exchange format; we have no folders for them.
                unfiled++;
            }
        }
        if (unfiled > 0) {
            lost.merge("filed entries with no folder here (relationships, discards)", unfiled, Integer::sum);
        }

        if (!elements.isEmpty() || !folder.children().isEmpty()) {
            elementsFolder = gateway.createFolder(
                    modelId, "elements", folder.label(), parents == null ? null : parents.elements());
            created++;
            for (String element : elements) {
                gateway.assignToFolder(modelId, "elements", element, elementsFolder);
            }
        }
        if (!views.isEmpty()) {
            viewsFolder = gateway.createFolder(
                    modelId, "views", folder.label(), parents == null ? null : parents.views());
            created++;
            for (String view : views) {
                gateway.assignToFolder(modelId, "views", view, viewsFolder);
            }
        }
        for (ParsedFolder child : folder.children()) {
            created += file(
                    modelId, child, new ParentFolders(elementsFolder, viewsFolder), elementSlug, viewSlug, graph, lost);
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
