package dev.archgrid.model;

import dev.archgrid.admin.workspace.WorkspaceService;
import dev.archgrid.domain.model.ModelCommand;
import dev.archgrid.domain.model.ModelGraph;
import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.WorkspaceId;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/workspaces/{workspaceKey}/models")
class ModelController {

    private final WorkspaceService workspaces;
    private final ModelService models;
    private final ModelCommandService commands;

    ModelController(WorkspaceService workspaces, ModelService models, ModelCommandService commands) {
        this.workspaces = workspaces;
        this.models = models;
        this.commands = commands;
    }

    @GetMapping
    List<ModelApiDtos.ModelResponse> list(@PathVariable String workspaceKey) {
        return models.list(workspace(workspaceKey)).stream()
                .map(ModelApiDtos.ModelResponse::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ResponseEntity<ModelApiDtos.ModelResponse> create(
            @PathVariable String workspaceKey, @Valid @RequestBody ModelApiDtos.CreateModelRequest request) {
        var row = models.create(
                workspace(workspaceKey), Slug.of(request.key()), request.name(), request.description());
        return ResponseEntity.created(URI.create("/api/workspaces/" + workspaceKey + "/models/" + row.key()))
                .body(ModelApiDtos.ModelResponse.from(row));
    }

    @GetMapping("/{modelKey}")
    ModelApiDtos.ModelResponse get(@PathVariable String workspaceKey, @PathVariable String modelKey) {
        return ModelApiDtos.ModelResponse.from(model(workspaceKey, modelKey));
    }

    /**
     * The only endpoint that changes a model.
     *
     * <p>There is no PUT on an element and no DELETE on a relationship, and that is the point:
     * every change is an attributed, validated, versioned commit. An update endpoint would be a
     * way around the metamodel (ADR-0004).
     */
    @PostMapping("/{modelKey}/commands")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR', 'OWNER', 'ADMIN')")
    ModelApiDtos.CommitResponse applyCommands(
            @PathVariable String workspaceKey,
            @PathVariable String modelKey,
            @Valid @RequestBody ModelApiDtos.CommandRequest request,
            Principal principal) {
        UUID modelId = model(workspaceKey, modelKey).id();
        List<ModelCommand> parsed =
                request.commands().stream().map(ModelApiDtos.CommandDto::toCommand).toList();

        var result = commands.apply(
                modelId,
                request.baseCommitId(),
                parsed,
                principal == null ? "anonymous" : principal.getName(),
                request.message());
        return new ModelApiDtos.CommitResponse(result.commitId(), result.elements(), result.relationships());
    }

    @GetMapping("/{modelKey}/elements")
    List<ModelApiDtos.ElementResponse> elements(@PathVariable String workspaceKey, @PathVariable String modelKey) {
        ModelGraph graph = commands.current(model(workspaceKey, modelKey).id());
        return graph.elements().stream().map(ModelApiDtos.ElementResponse::from).toList();
    }

    @GetMapping("/{modelKey}/relationships")
    List<ModelApiDtos.RelationshipResponse> relationships(
            @PathVariable String workspaceKey, @PathVariable String modelKey) {
        ModelGraph graph = commands.current(model(workspaceKey, modelKey).id());
        return graph.relationships().stream()
                .map(ModelApiDtos.RelationshipResponse::from)
                .toList();
    }

    /**
     * The specialisations defined in this model.
     *
     * <p>Model-scoped rather than part of the metamodel, because profiles are customer content
     * (ADR-0002). The editor merges them into the palette above the base concepts.
     */
    @GetMapping("/{modelKey}/profiles")
    List<ModelApiDtos.ProfileResponse> profiles(@PathVariable String workspaceKey, @PathVariable String modelKey) {
        return commands.profiles(model(workspaceKey, modelKey).id()).all().stream()
                .map(ModelApiDtos.ProfileResponse::from)
                .toList();
    }

    @GetMapping("/{modelKey}/commits")
    List<ModelApiDtos.CommitResponseItem> commits(
            @PathVariable String workspaceKey,
            @PathVariable String modelKey,
            @RequestParam(defaultValue = "50") int limit) {
        return commands.history(model(workspaceKey, modelKey).id(), Math.clamp(limit, 1, 500)).stream()
                .map(ModelApiDtos.CommitResponseItem::from)
                .toList();
    }

    private WorkspaceId workspace(String workspaceKey) {
        return workspaces.findByKey(Slug.of(workspaceKey))
                .map(found -> found.id())
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "No workspace '" + workspaceKey + "'."));
    }

    private ModelRepository.ModelRow model(String workspaceKey, String modelKey) {
        return models.find(workspace(workspaceKey), Slug.of(modelKey))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "No model '" + modelKey + "' in workspace '" + workspaceKey + "'."));
    }
}
