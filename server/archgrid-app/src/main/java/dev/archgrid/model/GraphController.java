package dev.archgrid.model;

import dev.archgrid.admin.workspace.WorkspaceService;
import dev.archgrid.domain.model.ModelCommand;
import dev.archgrid.domain.model.ModelGraph;
import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.Workspace;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    GraphController(WorkspaceService workspaces, GraphService graphs, ModelCommandService commands) {
        this.workspaces = workspaces;
        this.graphs = graphs;
        this.commands = commands;
    }

    @GetMapping
    ModelApiDtos.GraphResponse graph(@PathVariable String workspaceKey) {
        Workspace workspace = workspace(workspaceKey);
        ModelRepository.ModelRow row = graphs.of(workspace.id(), workspace.name());
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
        return commands.current(graphId(workspaceKey)).elements().stream()
                .map(ModelApiDtos.ElementResponse::from)
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
