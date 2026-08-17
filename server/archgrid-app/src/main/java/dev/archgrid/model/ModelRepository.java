package dev.archgrid.model;

import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The model registry: identity and metadata, not the graph. */
@Repository
class ModelRepository {

    private final JdbcClient jdbc;

    ModelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record ModelRow(UUID id, Slug key, String name, String description, UUID headCommitId) {}

    UUID create(WorkspaceId workspaceId, Slug key, String name, String description) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into model (id, workspace_id, \"key\", name, description) "
                        + "values (:id, :workspaceId, :key, :name, :description)")
                .param("id", id)
                .param("workspaceId", workspaceId.value())
                .param("key", key.value())
                .param("name", name)
                .param("description", description)
                .update();
        return id;
    }

    Optional<ModelRow> find(WorkspaceId workspaceId, Slug key) {
        return jdbc.sql("select id, \"key\", name, description, head_commit_id from model "
                        + "where workspace_id = :workspaceId and \"key\" = :key")
                .param("workspaceId", workspaceId.value())
                .param("key", key.value())
                .query(ModelRepository::mapRow)
                .optional();
    }

    List<ModelRow> findAll(WorkspaceId workspaceId) {
        return jdbc.sql("select id, \"key\", name, description, head_commit_id from model "
                        + "where workspace_id = :workspaceId order by \"key\"")
                .param("workspaceId", workspaceId.value())
                .query(ModelRepository::mapRow)
                .list();
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
