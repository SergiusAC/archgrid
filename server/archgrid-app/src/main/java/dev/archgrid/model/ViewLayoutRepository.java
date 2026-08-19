package dev.archgrid.model;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

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
    private final ObjectMapper json;

    ViewLayoutRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** `parentKey` is the element this one is drawn inside on this view — presentation only. */
    record LayoutNode(
            String elementKey, double x, double y, Double width, Double height, String parentKey, Integer z) {}

    List<LayoutNode> load(UUID modelId, String viewKey) {
        List<LayoutNode> nodes = new ArrayList<>();
        jdbc.sql(
                        """
                        select e."key" as element_key, n.x, n.y, n.width, n.height, n.z,
                               p."key" as parent_key
                        from view_layout_node n
                        join view v on v.id = n.view_id
                        join element e on e.id = n.element_id
                        left join element p on p.id = n.parent_element_id
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
                            (Double) rs.getObject("height"),
                            rs.getString("parent_key"),
                            (Integer) rs.getObject("z")));
                    return null;
                })
                .list();
        return nodes;
    }

    /** How this view routes its connections. Presentation, so it lives beside the coordinates. */
    String router(UUID modelId, String viewKey) {
        return jdbc.sql("select router from view where model_id = :modelId and \"key\" = :viewKey")
                .param("modelId", modelId)
                .param("viewKey", viewKey)
                .query(String.class)
                .optional()
                .orElse("straight");
    }

    void saveRouter(UUID modelId, String viewKey, String router) {
        jdbc.sql("update view set router = :router where model_id = :modelId and \"key\" = :viewKey")
                .param("router", router)
                .param("modelId", modelId)
                .param("viewKey", viewKey)
                .update();
    }

    /** A bendpoint. Model coordinates, the same ones the nodes are placed in. */
    record Point(double x, double y) {}

    /**
     * How one line is routed on one view: the points it is dragged through.
     *
     * <p>`linkKey` is the line as the diagram knows it — the key of a stated relationship, or the
     * computed key of a derived one. Keying by the relationship excluded every implied line, which
     * the editor let the user bend and then dropped in silence.
     */
    record LayoutLink(String linkKey, List<Point> vertices) {}

    /**
     * The routing of every line on this view.
     *
     * <p>A relationship is the same relationship whichever way its line is drawn, so this belongs
     * with the coordinates rather than with the model — and per view, because the same
     * relationship is drawn on many diagrams and each has its own boxes in the way.
     */
    List<LayoutLink> links(UUID modelId, String viewKey) {
        List<LayoutLink> links = new ArrayList<>();
        jdbc.sql(
                        """
                        select l.link_key, l.vertices::text as vertices
                        from view_layout_link l
                        join view v on v.id = l.view_id
                        where v.model_id = :modelId and v."key" = :viewKey
                        """)
                .param("modelId", modelId)
                .param("viewKey", viewKey)
                .query((ResultSet rs, int rowNum) -> {
                    links.add(new LayoutLink(rs.getString("link_key"), readVertices(rs.getString("vertices"))));
                    return null;
                })
                .list();
        return links;
    }

    /**
     * Replaces the routing of the lines named, and clears the routing of those sent empty.
     *
     * <p>Sent whole rather than as a change, like everything else in the layout: the client holds
     * the arrangement and the server stores what it is told.
     */
    void saveLinks(UUID modelId, String viewKey, List<LayoutLink> links) {
        for (LayoutLink link : links) {
            if (link.vertices().isEmpty()) {
                jdbc.sql(
                                """
                                delete from view_layout_link l
                                using view v
                                where l.view_id = v.id
                                  and v.model_id = :modelId and v."key" = :viewKey
                                  and l.link_key = :linkKey
                                """)
                        .param("modelId", modelId)
                        .param("viewKey", viewKey)
                        .param("linkKey", link.linkKey())
                        .update();
                continue;
            }
            // A left join, not a join: a derived line has no row in `relationship` to match, and
            // that is exactly the case this table used to lose. Where there is one, the reference
            // is kept so deleting the relationship takes its routing with it.
            jdbc.sql(
                            """
                            insert into view_layout_link (view_id, relationship_id, link_key, vertices)
                            select v.id, r.id, :linkKey, cast(:vertices as jsonb)
                            from view v
                            left join relationship r on r.model_id = v.model_id and r."key" = :linkKey
                            where v.model_id = :modelId and v."key" = :viewKey
                            on conflict (view_id, link_key)
                            do update set relationship_id = excluded.relationship_id,
                                          vertices = excluded.vertices,
                                          updated_at = now()
                            """)
                    .param("vertices", json.writeValueAsString(link.vertices()))
                    .param("linkKey", link.linkKey())
                    .param("modelId", modelId)
                    .param("viewKey", viewKey)
                    .update();
        }
    }

    private List<Point> readVertices(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Map<String, Double>> points = json.readValue(raw, new tools.jackson.core.type.TypeReference<>() {});
        return points.stream()
                .filter(point -> point.get("x") != null && point.get("y") != null)
                .map(point -> new Point(point.get("x"), point.get("y")))
                .toList();
    }

    /**
     * Relationships this view declines to draw.
     *
     * <p>The model still asserts them; hiding is a statement about one diagram, not about the
     * architecture (ADR-0011). Kept beside the coordinates for the same reason they are: nothing
     * here belongs in the history.
     */
    List<String> hidden(UUID modelId, String viewKey) {
        return jdbc.sql(
                        """
                        select r."key"
                        from view_hidden_relationship h
                        join view v on v.id = h.view_id
                        join relationship r on r.id = h.relationship_id
                        where v.model_id = :modelId and v."key" = :viewKey
                        """)
                .param("modelId", modelId)
                .param("viewKey", viewKey)
                .query(String.class)
                .list();
    }

    /** Replaces the set wholesale: the client sends what the view hides, not a change to it. */
    void saveHidden(UUID modelId, String viewKey, List<String> relationshipKeys) {
        jdbc.sql(
                        """
                        delete from view_hidden_relationship h
                        using view v
                        where v.id = h.view_id and v.model_id = :modelId and v."key" = :viewKey
                        """)
                .param("modelId", modelId)
                .param("viewKey", viewKey)
                .update();
        for (String key : relationshipKeys) {
            // Silently ignores a relationship that no longer exists, for the same reason `save`
            // ignores an element that has left the view: a race here costs a line being drawn.
            jdbc.sql(
                            """
                            insert into view_hidden_relationship (view_id, relationship_id)
                            select v.id, r.id
                            from view v
                            join relationship r on r.model_id = v.model_id and r."key" = :relationshipKey
                            where v.model_id = :modelId and v."key" = :viewKey
                            on conflict do nothing
                            """)
                    .param("relationshipKey", key)
                    .param("modelId", modelId)
                    .param("viewKey", viewKey)
                    .update();
        }
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
                            insert into view_layout_node (view_id, element_id, x, y, width, height,
                                                          parent_element_id, z)
                            select v.id, e.id, :x, :y, :width, :height,
                                   (select p.id from element p
                                     where p.model_id = v.model_id and p."key" = :parentKey),
                                   :z
                            from view v
                            join element e on e.model_id = v.model_id and e."key" = :elementKey
                            where v.model_id = :modelId and v."key" = :viewKey
                            on conflict (view_id, element_id)
                            do update set x = excluded.x, y = excluded.y,
                                          width = excluded.width, height = excluded.height,
                                          parent_element_id = excluded.parent_element_id,
                                          z = excluded.z,
                                          updated_at = now()
                            """)
                    .param("x", node.x())
                    .param("y", node.y())
                    .param("width", node.width())
                    .param("height", node.height())
                    .param("elementKey", node.elementKey())
                    .param("parentKey", node.parentKey())
                    .param("z", node.z())
                    .param("modelId", modelId)
                    .param("viewKey", viewKey)
                    .update();
        }
    }
}
