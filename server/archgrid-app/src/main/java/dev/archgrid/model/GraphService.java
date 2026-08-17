package dev.archgrid.model;

import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.WorkspaceId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the single architecture graph of a workspace, creating it on first use.
 *
 * <p>A workspace is the repository and holds exactly one connected graph (ADR-0008). The
 * {@code model} row still exists — it carries {@code head_commit_id} and is what every other
 * table points at — but it is an implementation detail rather than something a user names or
 * chooses between.
 *
 * <p>Provisioning lazily rather than at workspace creation means no read path ever has to cope
 * with a workspace that has no graph, including workspaces seeded by hand into the database.
 */
@Service
class GraphService {

    private static final Slug GRAPH_KEY = Slug.of("graph");

    private final ModelRepository repository;

    GraphService(ModelRepository repository) {
        this.repository = repository;
    }

    /**
     * What a caller outside this package may know about the graph.
     *
     * <p>Deliberately not {@code ModelRepository.ModelRow}: returning a repository's own type
     * makes every caller depend on the repository, which is exactly what the architecture rules
     * forbid controllers from doing.
     */
    record GraphRef(UUID id, UUID headCommitId) {}

    @Transactional
    GraphRef of(WorkspaceId workspaceId, String workspaceName) {
        return repository
                .find(workspaceId)
                .or(() -> {
                    // Concurrent first loads all reach here; the insert absorbs the conflict, so
                    // whichever request lost the race still reads the winner's row.
                    repository.createIfAbsent(workspaceId, GRAPH_KEY, workspaceName);
                    return repository.find(workspaceId);
                })
                .map(row -> new GraphRef(row.id(), row.headCommitId()))
                .orElseThrow();
    }
}
