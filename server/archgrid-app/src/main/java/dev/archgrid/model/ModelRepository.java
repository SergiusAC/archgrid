package dev.archgrid.model;

import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.WorkspaceId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The workspace's graph row.
 *
 * <p>Exactly one per workspace, enforced by a unique constraint since migration V4 (ADR-0008),
 * which is why there is no "find all" here — a query returning a list would invite code that
 * quietly handles a case the schema forbids.
 */
@Repository
class ModelRepository {

    private final JdbcClient jdbc;

    ModelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record ModelRow(UUID id, Slug key, String name, String description, UUID headCommitId) {}

    /**
     * Creates the workspace's graph unless it already has one.
     *
     * <p>{@code on conflict do nothing} rather than catching the constraint violation. A first
     * page load fires three requests at once and each would try to provision; the loser of that
     * race gets a unique-key error that has already aborted the PostgreSQL transaction, so any
     * recovery query inside it fails too, with a far less obvious message. Letting the database
     * absorb the conflict keeps the transaction usable.
     */
    void createIfAbsent(WorkspaceId workspaceId, Slug key, String name) {
        jdbc.sql("insert into model (id, workspace_id, \"key\", name) "
                        + "values (:id, :workspaceId, :key, :name) "
                        + "on conflict (workspace_id) do nothing")
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId.value())
                .param("key", key.value())
                .param("name", name)
                .update();
    }

    Optional<ModelRow> find(WorkspaceId workspaceId) {
        return jdbc.sql("select id, \"key\", name, description, head_commit_id from model "
                        + "where workspace_id = :workspaceId")
                .param("workspaceId", workspaceId.value())
                .query(ModelRepository::mapRow)
                .optional();
    }

    private static ModelRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ModelRow(
                rs.getObject("id", UUID.class),
                Slug.of(rs.getString("key")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("head_commit_id", UUID.class));
    }
}
