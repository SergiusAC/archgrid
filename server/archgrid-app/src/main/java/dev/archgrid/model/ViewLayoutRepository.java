package dev.archgrid.model;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Coordinates.
 *
 * <p>Outside the commit log entirely (ADR-0003). Dragging a box is not an architecture change,
 * and a history in which it appeared would be one nobody reads. Last write wins; a lost race
 * costs a nudge of the mouse, not information.
 */
@Repository
class ViewLayoutRepository {

    private final JdbcClient jdbc;

    ViewLayoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record LayoutNode(String elementKey, double x, double y, Double width, Double height) {}

    List<LayoutNode> load(UUID modelId, String viewKey) {
        List<LayoutNode> nodes = new ArrayList<>();
        jdbc.sql(
                        """
                        select e."key" as element_key, n.x, n.y, n.width, n.height
                        from view_layout_node n
                        join view v on v.id = n.view_id
                        join element e on e.id = n.element_id
                        where v.model_id = :modelId and v."key" = :viewKey
                        """)
                .param("modelId", modelId)
                .param("viewKey", viewKey)
                .query((ResultSet rs, int rowNum) -> {
                    nodes.add(new LayoutNode(
                            rs.getString("element_key"),
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            (Double) rs.getObject("width"),
                            (Double) rs.getObject("height")));
                    return null;
                })
                .list();
        return nodes;
    }

    /**
     * Upserts positions.
     *
     * <p>Silently ignores an element that is not on the view: a drag that races a removal should
     * not fail the request, and there is nothing useful to tell the user about it.
     */
    void save(UUID modelId, String viewKey, List<LayoutNode> nodes) {
        for (LayoutNode node : nodes) {
            jdbc.sql(
                            """
                            insert into view_layout_node (view_id, element_id, x, y, width, height)
                            select v.id, e.id, :x, :y, :width, :height
                            from view v
                            join element e on e.model_id = v.model_id and e."key" = :elementKey
                            where v.model_id = :modelId and v."key" = :viewKey
                            on conflict (view_id, element_id)
                            do update set x = excluded.x, y = excluded.y,
                                          width = excluded.width, height = excluded.height,
                                          updated_at = now()
                            """)
                    .param("x", node.x())
                    .param("y", node.y())
                    .param("width", node.width())
                    .param("height", node.height())
                    .param("elementKey", node.elementKey())
                    .param("modelId", modelId)
                    .param("viewKey", viewKey)
                    .update();
        }
    }
}
