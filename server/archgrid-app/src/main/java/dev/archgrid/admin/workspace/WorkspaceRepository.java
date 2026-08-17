package dev.archgrid.admin.workspace;

import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.Workspace;
import dev.archgrid.domain.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for workspaces, written as explicit SQL over {@link JdbcClient}.
 *
 * <p>No ORM. The core of this product is a versioned graph queried with recursive CTEs and
 * bulk-loaded into an in-memory index; an object-relational mapper contributes nothing to
 * those access patterns and actively obstructs them through identity management and lazy
 * loading. Hand-written SQL for a schema this small is cheaper to write and far cheaper to
 * reason about when a query is slow. See ADR-0001.
 */
@Repository
class WorkspaceRepository {

    private final JdbcClient jdbc;

    WorkspaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Workspace> findAll() {
        return jdbc.sql("select id, \"key\", name from workspace order by \"key\"")
                .query(WorkspaceRepository::mapRow)
                .list();
    }

    Optional<Workspace> findByKey(Slug key) {
        return jdbc.sql("select id, \"key\", name from workspace where \"key\" = :key")
                .param("key", key.value())
                .query(WorkspaceRepository::mapRow)
                .optional();
    }

    void create(Slug key, String name) {
        jdbc.sql("insert into workspace (id, \"key\", name) values (:id, :key, :name)")
                .param("id", java.util.UUID.randomUUID())
                .param("key", key.value())
                .param("name", name)
                .update();
    }

    private static Workspace mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Workspace(
                WorkspaceId.of(rs.getObject("id", UUID.class)),
                Slug.of(rs.getString("key")),
                rs.getString("name"));
    }
}
