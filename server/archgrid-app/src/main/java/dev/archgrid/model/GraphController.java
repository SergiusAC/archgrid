package dev.archgrid.model;

import dev.archgrid.admin.workspace.WorkspaceService;
import dev.archgrid.domain.model.ModelCommand;
import dev.archgrid.domain.metamodel.Metamodel;
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

    GraphController(
            WorkspaceService workspaces,
            GraphService graphs,
            ModelCommandService commands,
            FolderService folders,
            ViewLayoutService layout,
            Metamodel metamodel) {
        this.workspaces = workspaces;
        this.graphs = graphs;
        this.commands = commands;
        this.folders = folders;
        this.layout = layout;
        this.metamodel = metamodel;
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

        return new ModelApiDtos.ViewResponse(
                view.key().value(),
                view.name(),
                view.viewpoint(),
                folders.viewFolders(graphId).get(view.key().value()),
                view.elements().stream()
                        .map(graph::element)
                        .flatMap(java.util.Optional::stream)
                        .map(ModelApiDtos.ElementResponse::from)
                        .toList(),
                graph.relationshipsOn(view).stream()
                        .map(ModelApiDtos.RelationshipResponse::from)
                        .toList(),
                // Computed, never stored: what the model implies between the elements on this
                // view through elements it does not show. A context diagram that omitted these
                // would be lying by omission.
                RelationshipDerivation.derive(graph, view.elements(), metamodel).stream()
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
                layout.load(graphId, viewKey).stream()
                        .map(node -> new ModelApiDtos.LayoutNodeResponse(
                                node.elementKey(), node.x(), node.y(), node.width(), node.height(),
                                node.parentKey(), node.z()))
                        .toList(),
                layout.hidden(graphId, viewKey),
                layout.router(graphId, viewKey));
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
                                node.element(), node.x(), node.y(), node.width(), node.height(),
                                node.parent(), node.z()))
                        .toList());
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
