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

    /**
     * One drawing of an element on one view.
     *
     * <p>`id` is the placement, and an element may have several of them (ADR-0014): membership says
     * whether an element is on the view, this says how many times it is drawn and where. `parentId`
     * is the placement it sits inside — a copy may be nested while the original stands alone.
     */
    record LayoutNode(
            UUID id,
            String elementKey,
            double x,
            double y,
            Double width,
            Double height,
            UUID parentId,
            Integer z) {}

    List<LayoutNode> load(UUID modelId, String viewKey) {
        List<LayoutNode> nodes = new ArrayList<>();
        jdbc.sql(
                        """
                        select n.id, e."key" as element_key, n.x, n.y, n.width, n.height, n.z,
                               n.parent_node_id
                        from view_layout_node n
                        join view v on v.id = n.view_id
                        join element e on e.id = n.element_id
                        where v.model_id = :modelId and v."key" = :viewKey
                        order by n.z nulls first, n.id
                        """)
                .param("modelId", modelId)
                .param("viewKey", viewKey)
                .query((ResultSet rs, int rowNum) -> {
                    nodes.add(new LayoutNode(
                            rs.getObject("id", UUID.class),
                            rs.getString("element_key"),
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            (Double) rs.getObject("width"),
                            (Double) rs.getObject("height"),
                            rs.getObject("parent_node_id", UUID.class),
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
    record LayoutLink(String linkKey, UUID sourceNodeId, UUID targetNodeId, List<Point> vertices) {}

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
                        select l.link_key, l.source_node_id, l.target_node_id, l.vertices::text as vertices
                        from view_layout_link l
                        join view v on v.id = l.view_id
                        where v.model_id = :modelId and v."key" = :viewKey
                        """)
                .param("modelId", modelId)
                .param("viewKey", viewKey)
                .query((ResultSet rs, int rowNum) -> {
                    links.add(new LayoutLink(
                            rs.getString("link_key"),
                            rs.getObject("source_node_id", UUID.class),
                            rs.getObject("target_node_id", UUID.class),
                            readVertices(rs.getString("vertices"))));
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
            // Nothing to remember at all: no bendpoints and no chosen ends.
            if (link.vertices().isEmpty() && link.sourceNodeId() == null && link.targetNodeId() == null) {
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
                            insert into view_layout_link (view_id, relationship_id, link_key, vertices,
                                                          source_node_id, target_node_id)
                            select v.id, r.id, :linkKey, cast(:vertices as jsonb), :sourceNode, :targetNode
                            from view v
                            left join relationship r on r.model_id = v.model_id and r."key" = :linkKey
                            where v.model_id = :modelId and v."key" = :viewKey
                            on conflict (view_id, link_key)
                            do update set relationship_id = excluded.relationship_id,
                                          vertices = excluded.vertices,
                                          source_node_id = excluded.source_node_id,
                                          target_node_id = excluded.target_node_id,
                                          updated_at = now()
                            """)
                    .param("vertices", json.writeValueAsString(link.vertices()))
                    .param("sourceNode", link.sourceNodeId())
                    .param("targetNode", link.targetNodeId())
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
    /**
     * Writes the placements of a view, and removes the ones it no longer has.
     *
     * <p>Removal is new with placements and is not optional: a duplicate the user deleted would
     * otherwise be redrawn on the next load. The client sends the whole arrangement of the view —
     * including placements it is not currently drawing, because a filter narrows what is shown and
     * never what belongs — so anything absent from the payload is absent on purpose.
     *
     * <p>Written in two passes for the sake of one case: a placement nested inside another that is
     * itself new. The reference has to exist before it can be pointed at.
     */
    void save(UUID modelId, String viewKey, List<LayoutNode> nodes) {
        UUID viewId = viewId(modelId, viewKey);
        if (viewId == null) {
            return;
        }

        for (LayoutNode node : nodes) {
            jdbc.sql(
                            """
                            insert into view_layout_node (id, view_id, element_id, x, y, width, height, z)
                            select :id, v.id, e.id, :x, :y, :width, :height, :z
                            from view v
                            join element e on e.model_id = v.model_id and e."key" = :elementKey
                            where v.id = :viewId
                            on conflict (id)
                            do update set x = excluded.x, y = excluded.y,
                                          width = excluded.width, height = excluded.height,
                                          z = excluded.z,
                                          updated_at = now()
                            """)
                    .param("id", node.id())
                    .param("viewId", viewId)
                    .param("x", node.x())
                    .param("y", node.y())
                    .param("width", node.width())
                    .param("height", node.height())
                    .param("elementKey", node.elementKey())
                    .param("z", node.z())
                    .update();
        }

        for (LayoutNode node : nodes) {
            jdbc.sql("update view_layout_node set parent_node_id = :parentId where id = :id and view_id = :viewId")
                    .param("parentId", node.parentId())
                    .param("id", node.id())
                    .param("viewId", viewId)
                    .update();
        }

        List<UUID> kept = nodes.stream().map(LayoutNode::id).toList();
        if (kept.isEmpty()) {
            jdbc.sql("delete from view_layout_node where view_id = :viewId")
                    .param("viewId", viewId)
                    .update();
        } else {
            jdbc.sql("delete from view_layout_node where view_id = :viewId and id <> all (:kept)")
                    .param("viewId", viewId)
                    .param("kept", kept.toArray(UUID[]::new))
                    .update();
        }
    }

    private UUID viewId(UUID modelId, String viewKey) {
        return jdbc.sql("select id from view where model_id = :modelId and \"key\" = :viewKey")
                .param("modelId", modelId)
                .param("viewKey", viewKey)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }
}
