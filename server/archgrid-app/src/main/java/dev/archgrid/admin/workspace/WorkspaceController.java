package dev.archgrid.admin.workspace;

import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/{key}")
    ResponseEntity<WorkspaceResponse> byKey(@PathVariable String key) {
        return service.findByKey(Slug.of(key))
                .map(WorkspaceResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

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
