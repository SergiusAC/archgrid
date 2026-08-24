package dev.archgrid.model;

import dev.archgrid.admin.workspace.WorkspaceService;
import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the repository has been through: the commits, what each one changed, and the model as it
 * stood at any of them.
 *
 * <p>Read-only, and separate from {@link GraphController} because it answers a different question
 * — not "what is the architecture" but "how did it get here". There is no endpoint to revert a
 * commit and there should not be one here: undoing a change is itself a change, and every change
 * goes through {@code POST /commands} (ADR-0004).
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceKey}")
class HistoryController {

    private final WorkspaceService workspaces;
    private final GraphService graphs;
    private final HistoryService history;

    HistoryController(WorkspaceService workspaces, GraphService graphs, HistoryService history) {
        this.workspaces = workspaces;
        this.graphs = graphs;
        this.history = history;
    }

    /** Newest first. Bounded rather than paged: the page a reader starts on is always the last one. */
    @GetMapping("/commits")
    List<HistoryApiDtos.CommitEntry> commits(
            @PathVariable String workspaceKey, @RequestParam(defaultValue = "50") int limit) {
        return history.commits(graphId(workspaceKey), Math.clamp(limit, 1, 500)).stream()
                .map(HistoryApiDtos.CommitEntry::from)
                .toList();
    }

    /**
     * What one commit changed, or what separates two.
     *
     * @param from an earlier commit to compare against. Omitted, it is the commit's own parent,
     *     which is the question a log entry is usually being asked.
     */
    @GetMapping("/commits/{commitId}/diff")
    HistoryApiDtos.DiffResponse diff(
            @PathVariable String workspaceKey,
            @PathVariable UUID commitId,
            @RequestParam(required = false) UUID from) {
        return HistoryApiDtos.DiffResponse.from(history.diff(graphId(workspaceKey), from, commitId));
    }

    /**
     * The semantic model as it stood at a commit.
     *
     * <p>No layout, because none was versioned (ADR-0003). This is a repository to read, not a
     * diagram to reopen — the coordinates of a view are the ones it has now, whatever the model
     * looked like then.
     */
    @GetMapping("/commits/{commitId}/snapshot")
    HistoryApiDtos.SnapshotResponse snapshot(@PathVariable String workspaceKey, @PathVariable UUID commitId) {
        return HistoryApiDtos.SnapshotResponse.from(history.snapshot(graphId(workspaceKey), commitId));
    }

    private UUID graphId(String workspaceKey) {
        Workspace workspace = workspaces
                .findByKey(Slug.of(workspaceKey))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "No workspace '" + workspaceKey + "'."));
        return graphs.of(workspace.id(), workspace.name()).id();
    }
}
