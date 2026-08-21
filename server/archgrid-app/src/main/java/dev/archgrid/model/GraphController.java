package dev.archgrid.model;

import dev.archgrid.admin.workspace.WorkspaceService;
import dev.archgrid.domain.model.ModelCommand;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.c4.C4Level;
import dev.archgrid.domain.metamodel.c4.C4Mapping;
import dev.archgrid.domain.model.C4Projection;
import dev.archgrid.domain.model.ModelGraph;
import dev.archgrid.domain.model.RelationshipDerivation;
import dev.archgrid.domain.model.View;
import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.Workspace;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The architecture graph of a workspace.
 *
 * <p>There is no model segment in these paths, and that is the point: a workspace is the
 * repository and holds exactly one connected graph (ADR-0008). Making callers name a model
 * would reintroduce the possibility of several, and with it impact analysis that answers from
 * inside one of them and is silently wrong about the rest.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceKey}")
class GraphController {

    private final WorkspaceService workspaces;
    private final GraphService graphs;
    private final ModelCommandService commands;
    private final FolderService folders;
    private final ViewLayoutService layout;
    private final Metamodel metamodel;
    private final C4Mapping c4;

    GraphController(
            WorkspaceService workspaces,
            GraphService graphs,
            ModelCommandService commands,
            FolderService folders,
            ViewLayoutService layout,
            Metamodel metamodel,
            C4Mapping c4) {
        this.workspaces = workspaces;
        this.graphs = graphs;
        this.commands = commands;
        this.folders = folders;
        this.layout = layout;
        this.metamodel = metamodel;
        this.c4 = c4;
    }

    @GetMapping
    ModelApiDtos.GraphResponse graph(@PathVariable String workspaceKey) {
        Workspace workspace = workspace(workspaceKey);
        GraphService.GraphRef row = graphs.of(workspace.id(), workspace.name());
        ModelGraph current = commands.current(row.id());
        return new ModelApiDtos.GraphResponse(
                workspace.key().value(),
                workspace.name(),
                row.headCommitId(),
                current.elements().size(),
                current.relationships().size());
    }

    /**
     * The only endpoint that changes the graph.
     *
     * <p>No PUT on an element, no DELETE on a relationship. Every change is an attributed,
     * validated, versioned commit; an update endpoint would be a way around the metamodel
     * (ADR-0004).
     */
    @PostMapping("/commands")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR', 'OWNER', 'ADMIN')")
    ModelApiDtos.CommitResponse applyCommands(
            @PathVariable String workspaceKey,
            @Valid @RequestBody ModelApiDtos.CommandRequest request,
            Principal principal) {
        UUID graphId = graphId(workspaceKey);
        List<ModelCommand> parsed =
                request.commands().stream().map(ModelApiDtos.CommandDto::toCommand).toList();

        var result = commands.apply(
                graphId,
                request.baseCommitId(),
                parsed,
                principal == null ? "anonymous" : principal.getName(),
                request.message());
        return new ModelApiDtos.CommitResponse(result.commitId(), result.elements(), result.relationships());
    }

    @GetMapping("/elements")
    List<ModelApiDtos.ElementResponse> elements(@PathVariable String workspaceKey) {
        UUID graphId = graphId(workspaceKey);
        // Filing is read alongside rather than from the graph: a folder is organisation and is not
        // part of the model (ADR-0009). It was being stored and never returned, so the repository
        // tree always drew everything at the root and moving something appeared to do nothing.
        Map<String, String> folderOf = folders.elementFolders(graphId);
        return commands.current(graphId).elements().stream()
                .map(element -> ModelApiDtos.ElementResponse.from(element, folderOf.get(element.key().value())))
                .toList();
    }

    @GetMapping("/relationships")
    List<ModelApiDtos.RelationshipResponse> relationships(@PathVariable String workspaceKey) {
        return commands.current(graphId(workspaceKey)).relationships().stream()
                .map(ModelApiDtos.RelationshipResponse::from)
                .toList();
    }

    /**
     * The specialisations defined in this repository.
     *
     * <p>Workspace-scoped rather than part of the metamodel, because profiles are customer
     * content (ADR-0002).
     */
    @GetMapping("/profiles")
    List<ModelApiDtos.ProfileResponse> profiles(@PathVariable String workspaceKey) {
        return commands.profiles(graphId(workspaceKey)).all().stream()
                .map(ModelApiDtos.ProfileResponse::from)
                .toList();
    }

    /**
     * Defines a specialisation — "Microservice is a kind of Application Component".
     *
     * <p>This is how a customer extends the language without the product abandoning conformance,
     * which is why arbitrary per-tenant metamodels are still refused (ADR-0002). Until now the
     * feature could be read and validated against but never authored: nothing outside a test
     * fixture had ever written to the table.
     *
     * <p>Not a command, because profiles are not versioned by commits — the table carries no
     * validity range. That is a real limitation rather than a considered stance: the history
     * cannot say when the vocabulary changed. Versioning it is a migration and a decision.
     */
    @PostMapping("/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CONTRIBUTOR', 'OWNER', 'ADMIN')")
    ModelApiDtos.ProfileResponse defineProfile(
            @PathVariable String workspaceKey, @Valid @RequestBody ModelApiDtos.ProfileRequest request) {
        dev.archgrid.domain.profile.Profile profile;
        try {
            profile = request.toProfile();
        } catch (IllegalArgumentException ex) {
            // A bad attribute type or key: the message already names what was wrong.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        }
        try {
            commands.defineProfile(graphId(workspaceKey), profile);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // The catalogue's own rules, phrased as sentences a user can act on: the base concept
            // must exist and be specialisable, the name must not collide with a concept or with
            // another profile.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        }
        return ModelApiDtos.ProfileResponse.from(profile);
    }

    /**
     * Removes a specialisation.
     *
     * <p>The database refuses while any element version, current or historical, is typed by it.
     * That refusal is the feature: an element left claiming a type nothing defines is worse than
     * a profile nobody can delete.
     */
    @DeleteMapping("/profiles/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('CONTRIBUTOR', 'OWNER', 'ADMIN')")
    void removeProfile(@PathVariable String workspaceKey, @PathVariable String name) {
        try {
            if (!commands.removeProfile(graphId(workspaceKey), name)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No profile '" + name + "'.");
            }
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "'" + name + "' is still the type of at least one element, including in earlier versions "
                            + "of the model. Retype those elements before removing the profile.");
        }
    }

    /**
     * Everywhere an element is used: its relationships and the views that show it.
     *
     * <p>The views half matters most before a deletion. "It will disappear from 4 diagrams,
     * these ones" is a decision the architect can make; "it will disappear from everywhere" is
     * one they can only accept.
     */
    @GetMapping("/elements/{elementKey}/usage")
    ModelApiDtos.UsageResponse usage(@PathVariable String workspaceKey, @PathVariable String elementKey) {
        ModelGraph graph = commands.current(graphId(workspaceKey));
        Slug key = Slug.of(elementKey);
        if (graph.element(key).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No element '" + elementKey + "'.");
        }
        return new ModelApiDtos.UsageResponse(
                graph.relationshipsTouching(key).stream()
                        .map(ModelApiDtos.RelationshipResponse::from)
                        .toList(),
                graph.viewsShowing(key).stream()
                        .map(view -> new ModelApiDtos.ViewRefResponse(
                                view.key().value(), view.name()))
                        .toList());
    }

    // ------------------------------------------------------------------ views

    @GetMapping("/views")
    List<ModelApiDtos.ViewSummaryResponse> views(@PathVariable String workspaceKey) {
        UUID graphId = graphId(workspaceKey);
        Map<String, String> byView = folders.viewFolders(graphId);
        return commands.current(graphId).views().stream()
                .map(view -> ModelApiDtos.ViewSummaryResponse.from(view, byView.get(view.key().value())))
                .toList();
    }

    /** A view with everything needed to draw it: its elements, the relationships between them, and layout. */
    @GetMapping("/views/{viewKey}")
    ModelApiDtos.ViewResponse view(@PathVariable String workspaceKey, @PathVariable String viewKey) {
        UUID graphId = graphId(workspaceKey);
        ModelGraph graph = commands.current(graphId);
        View view = graph.view(Slug.of(viewKey))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No view '" + viewKey + "'."));

        // Filtering happens here rather than in the browser, and that is the whole value of it: a
        // view narrowed to the application layer derives the application-level lines that run
        // through the technology it no longer draws.
        java.util.Set<Slug> shown = dev.archgrid.domain.model.ViewFilters.visible(graph, view, metamodel);

        return new ModelApiDtos.ViewResponse(
                view.key().value(),
                view.name(),
                view.viewpoint(),
                folders.viewFolders(graphId).get(view.key().value()),
                shown.stream()
                        .map(graph::element)
                        .flatMap(java.util.Optional::stream)
                        .map(ModelApiDtos.ElementResponse::from)
                        .toList(),
                graph.relationshipsAmong(shown).stream()
                        .map(ModelApiDtos.RelationshipResponse::from)
                        .toList(),
                // A view draws what the model states and nothing else: implied relationships are
                // not sent here, by decision (ADR-0013). The engine that computes them is intact
                // and still answers for the C4 projection, where rolling a neighbour up to its
                // system leaves nothing else to draw the line from.
                layout.load(graphId, viewKey).stream()
                        .map(node -> new ModelApiDtos.LayoutNodeResponse(
                                node.id(), node.elementKey(), node.x(), node.y(), node.width(), node.height(),
                                node.parentId(), node.z()))
                        .toList(),
                layout.links(graphId, viewKey).stream()
                        .map(link -> new ModelApiDtos.LayoutLinkResponse(
                                link.linkKey(),
                                link.sourceNodeId(),
                                link.targetNodeId(),
                                link.vertices().stream()
                                        .map(point -> new ModelApiDtos.PointDto(point.x(), point.y()))
                                        .toList()))
                        .toList(),
                view.filters().stream().map(ModelApiDtos.FilterDto::from).toList(),
                // Named rather than counted: a diagram narrowed by a filter must not read as the
                // whole picture, and "3 hidden" without saying which is an invitation to guess.
                view.elements().stream()
                        .filter(member -> !shown.contains(member))
                        .map(graph::element)
                        .flatMap(java.util.Optional::stream)
                        .map(element -> new ModelApiDtos.OmittedResponse(
                                element.key().value(), element.name(), element.displayType()))
                        .toList(),
                layout.hidden(graphId, viewKey),
                layout.router(graphId, viewKey));
    }

    // ------------------------------------------------------------------ C4

    /**
     * The graph seen as C4, around one element.
     *
     * <p>Computed on demand and stored nowhere — no element carries a C4 type, because whether
     * something is a system or a container depends on where the boundary is drawn (ADR-0007). What
     * comes back is an ordinary set of elements and lines, so the client can draw it, and then, if
     * the architect wants to keep it, create a view from it with the commands it already has.
     *
     * <p>The omitted list is part of the answer, not an aside: C4 has no form for behaviour,
     * interfaces, motivation or strategy, and a diagram that quietly dropped them would be
     * claiming a completeness it does not have.
     */
    @GetMapping("/projections/c4")
    ModelApiDtos.C4ProjectionResponse c4Projection(
            @PathVariable String workspaceKey, @RequestParam String focus, @RequestParam String level) {
        UUID graphId = graphId(workspaceKey);
        ModelGraph graph = commands.current(graphId);
        C4Level c4Level = c4.level(level)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "Unknown C4 level '" + level + "'. Use one of: "
                                + c4.levels().stream().map(C4Level::code).toList() + "."));
        Slug focusKey = Slug.of(focus);
        if (graph.element(focusKey).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No element '" + focus + "'.");
        }

        C4Projection.Result result = C4Projection.project(graph, metamodel, c4, focusKey, c4Level)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, explainRefusal(graph, focusKey, c4Level)));

        java.util.Set<Slug> shown = new java.util.LinkedHashSet<>(result.shown());
        return new ModelApiDtos.C4ProjectionResponse(
                c4Level.code(),
                c4Level.name(),
                focus,
                result.shown().stream()
                        .map(graph::element)
                        .flatMap(java.util.Optional::stream)
                        .map(ModelApiDtos.ElementResponse::from)
                        .toList(),
                graph.relationshipsAmong(shown).stream()
                        .map(ModelApiDtos.RelationshipResponse::from)
                        .toList(),
                // The lines the model implies between what is shown, through everything the level
                // hides. On a context diagram that is most of the lines there are.
                RelationshipDerivation.derive(graph, shown, metamodel).stream()
                        .map(derived -> new ModelApiDtos.DerivedRelationshipResponse(
                                derived.key(),
                                derived.type().concept(),
                                derived.source().value(),
                                derived.target().value(),
                                derived.via().stream()
                                        .map(hidden -> graph.element(hidden)
                                                .map(dev.archgrid.domain.model.Element::name)
                                                .orElseGet(hidden::value))
                                        .toList()))
                        .toList(),
                result.omitted().stream()
                        .map(graph::element)
                        .flatMap(java.util.Optional::stream)
                        .map(element -> new ModelApiDtos.OmittedResponse(
                                element.key().value(), element.name(), element.displayType()))
                        .toList());
    }

    /** Says which of the two reasons applies, because "cannot be drawn" teaches nobody anything. */
    private String explainRefusal(ModelGraph graph, Slug focus, C4Level level) {
        String type = graph.element(focus)
                .map(dev.archgrid.domain.model.Element::displayType)
                .orElse("?");
        return "C4 has no way to draw a " + type + " at the " + level.name() + " level, so there is no "
                + "diagram to be had around '" + focus.value() + "'. A C4 view is drawn around a system, "
                + "a container or a person.";
    }

    /**
     * Saves coordinates.
     *
     * <p>A PUT rather than a command, and that asymmetry is the point: layout is the one layer
     * outside the commit log, so dragging a box never appears in the history (ADR-0003).
     */
    @PutMapping("/views/{viewKey}/layout")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR', 'OWNER', 'ADMIN')")
    void saveLayout(
            @PathVariable String workspaceKey,
            @PathVariable String viewKey,
            @Valid @RequestBody ModelApiDtos.LayoutRequest request) {
        UUID graphId = graphId(workspaceKey);
        layout.save(
                graphId,
                viewKey,
                request.nodes().stream()
                        .map(node -> new dev.archgrid.model.ViewLayoutRepository.LayoutNode(
                                // A placement the client has not named is one it has just drawn.
                                node.id() == null ? UUID.randomUUID() : node.id(),
                                node.element(),
                                node.x(),
                                node.y(),
                                node.width(),
                                node.height(),
                                node.parent(),
                                node.z()))
                        .toList());
        if (request.links() != null) {
            layout.saveLinks(
                    graphId,
                    viewKey,
                    request.links().stream()
                            .map(link -> new dev.archgrid.model.ViewLayoutRepository.LayoutLink(
                                    link.key(),
                                    link.sourceNode(),
                                    link.targetNode(),
                                    (link.vertices() == null ? List.<ModelApiDtos.PointDto>of() : link.vertices())
                                            .stream()
                                                    .map(point -> new dev.archgrid.model.ViewLayoutRepository.Point(
                                                            point.x(), point.y()))
                                                    .toList()))
                            .toList());
        }
        // Only when asked. A drag saves coordinates several times a second and must not carry an
        // opinion about routing with it.
        if (request.router() != null) {
            if (!request.router().equals("straight") && !request.router().equals("orthogonal")) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "Unknown connection router '" + request.router() + "'. Use 'straight' or 'orthogonal'.");
            }
            layout.saveRouter(graphId, viewKey, request.router());
        }
        if (request.hidden() != null) {
            layout.saveHidden(graphId, viewKey, request.hidden());
        }
    }

    // ------------------------------------------------------------------ folders

    @GetMapping("/folders")
    List<ModelApiDtos.FolderResponse> listFolders(
            @PathVariable String workspaceKey, @RequestParam(defaultValue = "elements") String kind) {
        return folders.list(graphId(workspaceKey), kind);
    }

    @PostMapping("/folders")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR', 'OWNER', 'ADMIN')")
    ModelApiDtos.FolderResponse createFolder(
            @PathVariable String workspaceKey,
            @RequestParam(defaultValue = "elements") String kind,
            @Valid @RequestBody ModelApiDtos.FolderRequest request) {
        return folders.create(graphId(workspaceKey), kind, request.name(), request.parent());
    }

    @PutMapping("/folders/{folderId}")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR', 'OWNER', 'ADMIN')")
    ModelApiDtos.FolderResponse updateFolder(
            @PathVariable String workspaceKey,
            @PathVariable UUID folderId,
            @Valid @RequestBody ModelApiDtos.FolderRequest request) {
        return folders.update(graphId(workspaceKey), folderId, request.name(), request.parent());
    }

    @DeleteMapping("/folders/{folderId}")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR', 'OWNER', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteFolder(@PathVariable String workspaceKey, @PathVariable UUID folderId) {
        folders.delete(graphId(workspaceKey), folderId);
    }

    /** Puts an element or a view into a folder. A null folder moves it to the root. */
    @PutMapping("/folders/assign")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR', 'OWNER', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void assign(
            @PathVariable String workspaceKey,
            @RequestParam String kind,
            @RequestParam String key,
            @RequestParam(required = false) UUID folder) {
        folders.assign(graphId(workspaceKey), kind, key, folder);
    }

    @GetMapping("/commits")
    List<ModelApiDtos.CommitResponseItem> commits(
            @PathVariable String workspaceKey, @RequestParam(defaultValue = "50") int limit) {
        return commands.history(graphId(workspaceKey), Math.clamp(limit, 1, 500)).stream()
                .map(ModelApiDtos.CommitResponseItem::from)
                .toList();
    }

    private Workspace workspace(String workspaceKey) {
        return workspaces
                .findByKey(Slug.of(workspaceKey))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "No workspace '" + workspaceKey + "'."));
    }

    private UUID graphId(String workspaceKey) {
        Workspace workspace = workspace(workspaceKey);
        return graphs.of(workspace.id(), workspace.name()).id();
    }
}
