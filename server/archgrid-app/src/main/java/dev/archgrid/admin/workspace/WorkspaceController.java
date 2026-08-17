package dev.archgrid.admin.workspace;

import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.Workspace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces")
class WorkspaceController {

    private final WorkspaceService service;

    WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    List<WorkspaceResponse> list() {
        return service.listAll().stream().map(WorkspaceResponse::from).toList();
    }

    /**
     * Creates a repository.
     *
     * <p>Admin-only: a workspace is a tenancy and access-control boundary, and the cost of one
     * more is a whole separate landscape with its own history. Its graph is provisioned lazily
     * on first read, so there is nothing else to set up.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<WorkspaceResponse> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        Workspace created = service.create(Slug.of(request.key()), request.name());
        return ResponseEntity.created(URI.create("/api/workspaces/" + created.key()))
                .body(WorkspaceResponse.from(created));
    }

    // GET /api/workspaces/{key} deliberately lives in the model slice, not here. Since ADR-0008
    // the workspace *is* the repository, so its detail response is the graph summary — name,
    // head commit and element counts — and having two controllers answer the same path would be
    // an ambiguous mapping resolved by whichever Spring happened to pick.

    record CreateWorkspaceRequest(
            @NotBlank @Size(max = 64) String key, @NotBlank @Size(max = 200) String name) {}

    /**
     * Wire format for a workspace.
     *
     * <p>Kept separate from {@link Workspace} on purpose: the domain record is free to
     * change shape as the model grows, while this record is a published contract that the
     * web client and the future CLI depend on.
     */
    record WorkspaceResponse(UUID id, String key, String name) {

        static WorkspaceResponse from(Workspace workspace) {
            return new WorkspaceResponse(
                    workspace.id().value(), workspace.key().value(), workspace.name());
        }
    }
}
