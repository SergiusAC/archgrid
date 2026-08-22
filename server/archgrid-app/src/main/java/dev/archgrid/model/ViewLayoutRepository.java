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
 *
 * <p>Views and elements are addressed by their ids here, as everywhere since docs/09; the model
 * id still travels with every call so that a view id from another workspace resolves to nothing
 * rather than to somebody else's diagram.
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
            UUID elementId,
            double x,
            double y,
            Double width,
            Double height,
            UUID parentId,
            Integer z) {}

    List<LayoutNode> load(UUID modelId, UUID viewId) {
        List<LayoutNode> nodes = new ArrayList<>();
        jdbc.sql(
                        """
                        select n.id, n.element_id, n.x, n.y, n.width, n.height, n.z, n.parent_node_id
                        from view_layout_node n
                        join view v on v.id = n.view_id
                        where v.model_id = :modelId and v.id = :viewId
                        order by n.z nulls first, n.id
                        """)
                .param("modelId", modelId)
                .param("viewId", viewId)
                .query((ResultSet rs, int rowNum) -> {
                    nodes.add(new LayoutNode(
                            rs.getObject("id", UUID.class),
                            rs.getObject("element_id", UUID.class),
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
    String router(UUID modelId, UUID viewId) {
        return jdbc.sql("select router from view where model_id = :modelId and id = :viewId")
                .param("modelId", modelId)
                .param("viewId", viewId)
                .query(String.class)
                .optional()
                .orElse("straight");
    }

    void saveRouter(UUID modelId, UUID viewId, String router) {
        jdbc.sql("update view set router = :router where model_id = :modelId and id = :viewId")
                .param("router", router)
                .param("modelId", modelId)
                .param("viewId", viewId)
                .update();
    }

    /** A bendpoint. Model coordinates, the same ones the nodes are placed in. */
    record Point(double x, double y) {}

    /**
     * One line on one view.
     *
     * <p>`id` is the line; `linkKey` is what it stands for — a relationship id, or a derived
     * line's computed key — and since V15 a relationship may be drawn by several lines
     * (ADR-0014). `sourceNodeId`/`targetNodeId` are the placements the ends attach to; null means
     * "wherever the element is drawn".
     */
    record LayoutLink(UUID id, String linkKey, UUID sourceNodeId, UUID targetNodeId, List<Point> vertices) {}

    /**
     * The routing of every line on this view.
     *
     * <p>A relationship is the same relationship whichever way its line is drawn, so this belongs
     * with the coordinates rather than with the model — and per view, because the same
     * relationship is drawn on many diagrams and each has its own boxes in the way.
     */
    List<LayoutLink> links(UUID modelId, UUID viewId) {
        List<LayoutLink> links = new ArrayList<>();
        jdbc.sql(
                        """
                        select l.id, l.link_key, l.source_node_id, l.target_node_id, l.vertices::text as vertices
                        from view_layout_link l
                        join view v on v.id = l.view_id
                        where v.model_id = :modelId and v.id = :viewId
                        """)
                .param("modelId", modelId)
                .param("viewId", viewId)
                .query((ResultSet rs, int rowNum) -> {
                    links.add(new LayoutLink(
                            rs.getObject("id", UUID.class),
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
     * Writes the lines of a view, and removes the ones it no longer has.
     *
     * <p>The same shape as the nodes' save since V15: the client sends every line worth storing,
     * and anything absent from the payload is absent on purpose — a straightened extra line, a
     * removed duplicate. A relationship whose only line carries nothing simply has no row, which
     * is every line on a view where nothing is drawn twice.
     */
    void saveLinks(UUID modelId, UUID viewId, List<LayoutLink> links) {
        if (!viewExists(modelId, viewId)) {
            return;
        }

        for (LayoutLink link : links) {
            // A left join, not a join: a derived line has no row in `relationship` to match — its
            // key is not even an id. Where there is one, the reference is kept so deleting the
            // relationship takes its lines with it.
            jdbc.sql(
                            """
                            insert into view_layout_link (id, view_id, relationship_id, link_key, vertices,
                                                          source_node_id, target_node_id)
                            select :id, v.id, r.id, :linkKey, cast(:vertices as jsonb), :sourceNode, :targetNode
                            from view v
                            left join relationship r
                                   on r.model_id = v.model_id and r.id = cast(:relationshipId as uuid)
                            where v.id = :viewId
                            on conflict (id)
                            do update set relationship_id = excluded.relationship_id,
                                          vertices = excluded.vertices,
                                          source_node_id = excluded.source_node_id,
                                          target_node_id = excluded.target_node_id,
                                          updated_at = now()
                            """)
                    .param("id", link.id())
                    .param("viewId", viewId)
                    .param("vertices", json.writeValueAsString(link.vertices()))
                    .param("sourceNode", link.sourceNodeId())
                    .param("targetNode", link.targetNodeId())
                    .param("linkKey", link.linkKey())
                    .param("relationshipId", asUuid(link.linkKey()))
                    .update();
        }

        List<UUID> kept = links.stream().map(LayoutLink::id).toList();
        if (kept.isEmpty()) {
            jdbc.sql("delete from view_layout_link where view_id = :viewId")
                    .param("viewId", viewId)
                    .update();
        } else {
            jdbc.sql("delete from view_layout_link where view_id = :viewId and id <> all (:kept)")
                    .param("viewId", viewId)
                    .param("kept", kept.toArray(UUID[]::new))
                    .update();
        }
    }

    /** The relationship a line key names, or null for a derived line's computed key. */
    private static UUID asUuid(String linkKey) {
        try {
            return UUID.fromString(linkKey);
        } catch (IllegalArgumentException notAnId) {
            return null;
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
    List<UUID> hidden(UUID modelId, UUID viewId) {
        return jdbc.sql(
                        """
                        select h.relationship_id
                        from view_hidden_relationship h
                        join view v on v.id = h.view_id
                        where v.model_id = :modelId and v.id = :viewId
                        """)
                .param("modelId", modelId)
                .param("viewId", viewId)
                .query(UUID.class)
                .list();
    }

    /** Replaces the set wholesale: the client sends what the view hides, not a change to it. */
    void saveHidden(UUID modelId, UUID viewId, List<UUID> relationshipIds) {
        jdbc.sql(
                        """
                        delete from view_hidden_relationship h
                        using view v
                        where v.id = h.view_id and v.model_id = :modelId and v.id = :viewId
                        """)
                .param("modelId", modelId)
                .param("viewId", viewId)
                .update();
        for (UUID relationshipId : relationshipIds) {
            // Silently ignores a relationship that no longer exists, for the same reason `save`
            // ignores an element that has left the view: a race here costs a line being drawn.
            jdbc.sql(
                            """
                            insert into view_hidden_relationship (view_id, relationship_id)
                            select v.id, r.id
                            from view v
                            join relationship r on r.model_id = v.model_id and r.id = :relationshipId
                            where v.model_id = :modelId and v.id = :viewId
                            on conflict do nothing
                            """)
                    .param("relationshipId", relationshipId)
                    .param("modelId", modelId)
                    .param("viewId", viewId)
                    .update();
        }
    }

    /**
     * Writes the placements of a view, and removes the ones it no longer has.
     *
     * <p>Removal is new with placements and is not optional: a duplicate the user deleted would
     * otherwise be redrawn on the next load. The client sends the whole arrangement of the view —
     * including placements it is not currently drawing, because a filter narrows what is shown and
     * never what belongs — so anything absent from the payload is absent on purpose.
     *
     * <p>Silently ignores an element that is not in this model: a drag that races a deletion should
     * not fail the request, and there is nothing useful to tell the user about it.
     *
     * <p>Written in two passes for the sake of one case: a placement nested inside another that is
     * itself new. The reference has to exist before it can be pointed at.
     */
    void save(UUID modelId, UUID viewId, List<LayoutNode> nodes) {
        if (!viewExists(modelId, viewId)) {
            return;
        }

        for (LayoutNode node : nodes) {
            jdbc.sql(
                            """
                            insert into view_layout_node (id, view_id, element_id, x, y, width, height, z)
                            select :id, v.id, e.id, :x, :y, :width, :height, :z
                            from view v
                            join element e on e.model_id = v.model_id and e.id = :elementId
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
                    .param("elementId", node.elementId())
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

    private boolean viewExists(UUID modelId, UUID viewId) {
        return jdbc.sql("select id from view where model_id = :modelId and id = :viewId")
                .param("modelId", modelId)
                .param("viewId", viewId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }
}
