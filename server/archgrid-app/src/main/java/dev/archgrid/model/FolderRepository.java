package dev.archgrid.model;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Folders: organisation, not meaning.
 *
 * <p>Plain CRUD rather than commands, because folders are deliberately not versioned (ADR-0009).
 * Reorganising is tidying; putting it in the commit log would bury "added a relationship" under
 * "moved to a folder", which is the same argument that keeps layout out of history.
 */
@Repository
class FolderRepository {

    private final JdbcClient jdbc;

    FolderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record FolderRow(UUID id, UUID parentId, String kind, String name) {}

    List<FolderRow> findAll(UUID modelId, String kind) {
        return jdbc.sql("select id, parent_id, kind, name from folder "
                        + "where model_id = :modelId and kind = :kind order by name")
                .param("modelId", modelId)
                .param("kind", kind)
                .query(FolderRepository::mapRow)
                .list();
    }

    Optional<FolderRow> find(UUID modelId, UUID id) {
        return jdbc.sql("select id, parent_id, kind, name from folder where model_id = :modelId and id = :id")
                .param("modelId", modelId)
                .param("id", id)
                .query(FolderRepository::mapRow)
                .optional();
    }

    UUID create(UUID modelId, UUID parentId, String kind, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into folder (id, model_id, parent_id, kind, name) "
                        + "values (:id, :modelId, :parentId, :kind, :name)")
                .param("id", id)
                .param("modelId", modelId)
                .param("parentId", parentId)
                .param("kind", kind)
                .param("name", name)
                .update();
        return id;
    }

    void rename(UUID modelId, UUID id, String name) {
        jdbc.sql("update folder set name = :name, updated_at = now() where model_id = :modelId and id = :id")
                .param("name", name)
                .param("modelId", modelId)
                .param("id", id)
                .update();
    }

    void move(UUID modelId, UUID id, UUID parentId) {
        jdbc.sql("update folder set parent_id = :parentId, updated_at = now() "
                        + "where model_id = :modelId and id = :id")
                .param("parentId", parentId)
                .param("modelId", modelId)
                .param("id", id)
                .update();
    }

    /**
     * Deletes a folder without deleting what is in it.
     *
     * <p>Children and contents move up to the parent. A folder is a label, not an owner — and a
     * delete that silently took a subtree of the repository with it would be the most expensive
     * possible reading of "remove this folder".
     */
    void delete(UUID modelId, UUID id) {
        UUID parentId = find(modelId, id).map(FolderRow::parentId).orElse(null);
        jdbc.sql("update folder set parent_id = :parent where model_id = :modelId and parent_id = :id")
                .param("parent", parentId)
                .param("modelId", modelId)
                .param("id", id)
                .update();
        jdbc.sql("update element set folder_id = :parent where model_id = :modelId and folder_id = :id")
                .param("parent", parentId)
                .param("modelId", modelId)
                .param("id", id)
                .update();
        jdbc.sql("update view set folder_id = :parent where model_id = :modelId and folder_id = :id")
                .param("parent", parentId)
                .param("modelId", modelId)
                .param("id", id)
                .update();
        jdbc.sql("delete from folder where model_id = :modelId and id = :id")
                .param("modelId", modelId)
                .param("id", id)
                .update();
    }

    /**
     * True when making {@code parentId} the parent of {@code id} would create a cycle.
     *
     * <p>The database cannot express this, and a cycle makes the tree infinite to render.
     */
    boolean wouldCycle(UUID modelId, UUID id, UUID parentId) {
        Set<UUID> seen = new LinkedHashSet<>();
        UUID current = parentId;
        while (current != null) {
            if (current.equals(id) || !seen.add(current)) {
                return true;
            }
            current = find(modelId, current).map(FolderRow::parentId).orElse(null);
        }
        return false;
    }

    void assignElement(UUID modelId, UUID elementId, UUID folderId) {
        jdbc.sql("update element set folder_id = :folder where model_id = :modelId and id = :key")
                .param("folder", folderId)
                .param("modelId", modelId)
                .param("key", elementId)
                .update();
    }

    void assignView(UUID modelId, UUID viewId, UUID folderId) {
        jdbc.sql("update view set folder_id = :folder where model_id = :modelId and id = :key")
                .param("folder", folderId)
                .param("modelId", modelId)
                .param("key", viewId)
                .update();
    }

    /** element or view id -&gt; folder id, for whichever table is asked about. */
    List<String[]> assignments(UUID modelId, String table) {
        List<String[]> rows = new ArrayList<>();
        jdbc.sql("select id::text as \"key\", folder_id from %s where model_id = :modelId and folder_id is not null"
                        .formatted(table))
                .param("modelId", modelId)
                .query((ResultSet rs, int rowNum) -> {
                    rows.add(new String[] {rs.getString("key"), rs.getString("folder_id")});
                    return null;
                })
                .list();
        return rows;
    }

    private static FolderRow mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FolderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("kind"),
                rs.getString("name"));
    }
}
