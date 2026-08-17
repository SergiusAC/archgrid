package dev.archgrid.model;

import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.model.Element;
import dev.archgrid.domain.model.ModelGraph;
import dev.archgrid.domain.model.Relationship;
import dev.archgrid.domain.model.View;
import dev.archgrid.domain.profile.AttributeDefinition;
import dev.archgrid.domain.profile.AttributeType;
import dev.archgrid.domain.profile.Profile;
import dev.archgrid.domain.profile.ProfileCatalog;
import dev.archgrid.domain.shared.Slug;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads and writes the versioned semantic graph.
 *
 * <p>Explicit SQL over {@link JdbcClient}, no ORM (ADR-0001). Validity ranges rather than a
 * materialised current-state table: a version row is open while {@code to_seq} is null, and a
 * change closes the old row and opens a new one in the same transaction.
 *
 * <p>Writing works by diffing the before and after graphs rather than by recording what each
 * command did. Two commands that cancel out therefore produce no rows at all, which keeps the
 * history a log of what changed instead of a log of what was attempted.
 */
@Repository
class ModelGraphRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ModelGraphRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record Head(UUID commitId, long seq) {

        static Head initial() {
            return new Head(null, 0L);
        }
    }

    // ------------------------------------------------------------------ reading

    Head head(UUID modelId) {
        return jdbc.sql("select c.id, c.seq from model m join model_commit c on c.id = m.head_commit_id "
                        + "where m.id = :modelId")
                .param("modelId", modelId)
                .query((rs, rowNum) -> new Head(rs.getObject("id", UUID.class), rs.getLong("seq")))
                .optional()
                .orElseGet(Head::initial);
    }

    ModelGraph loadCurrent(UUID modelId) {
        Map<Slug, Map<String, String>> elementProperties = loadProperties(modelId, "element");
        Map<Slug, Map<String, String>> relationshipProperties = loadProperties(modelId, "relationship");

        List<Element> elements = jdbc.sql(
                        """
                        select e."key" as element_key,
                               ev.concept_type,
                               p.name as profile_name,
                               ev.name,
                               ev.documentation,
                               parent."key" as parent_key
                        from element_version ev
                        join element e on e.id = ev.element_id
                        left join profile p on p.id = ev.profile_id
                        left join element parent on parent.id = ev.parent_element_id
                        where e.model_id = :modelId and ev.to_seq is null
                        order by e."key"
                        """)
                .param("modelId", modelId)
                .query((rs, rowNum) -> {
                    Slug key = Slug.of(rs.getString("element_key"));
                    String parent = rs.getString("parent_key");
                    return new Element(
                            key,
                            rs.getString("concept_type"),
                            rs.getString("profile_name"),
                            rs.getString("name"),
                            rs.getString("documentation"),
                            elementProperties.getOrDefault(key, Map.of()),
                            parent == null ? null : Slug.of(parent));
                })
                .list();

        List<Relationship> relationships = jdbc.sql(
                        """
                        select r."key" as relationship_key,
                               rv.concept_type,
                               s."key" as source_key,
                               t."key" as target_key,
                               rv.name
                        from relationship_version rv
                        join relationship r on r.id = rv.relationship_id
                        join element s on s.id = rv.source_id
                        join element t on t.id = rv.target_id
                        where r.model_id = :modelId and rv.to_seq is null
                        order by r."key"
                        """)
                .param("modelId", modelId)
                .query((rs, rowNum) -> {
                    Slug key = Slug.of(rs.getString("relationship_key"));
                    return new Relationship(
                            key,
                            rs.getString("concept_type"),
                            Slug.of(rs.getString("source_key")),
                            Slug.of(rs.getString("target_key")),
                            rs.getString("name"),
                            relationshipProperties.getOrDefault(key, Map.of()));
                })
                .list();

        return ModelGraph.of(elements, relationships, loadViews(modelId));
    }

    private List<View> loadViews(UUID modelId) {
        Map<Slug, Set<Slug>> members = new LinkedHashMap<>();
        jdbc.sql(
                        """
                        select v."key" as view_key, e."key" as element_key
                        from view_element ve
                        join view v on v.id = ve.view_id
                        join element e on e.id = ve.element_id
                        where v.model_id = :modelId and ve.to_seq is null
                        order by e."key"
                        """)
                .param("modelId", modelId)
                .query((ResultSet rs, int rowNum) -> {
                    members.computeIfAbsent(Slug.of(rs.getString("view_key")), key -> new LinkedHashSet<>())
                            .add(Slug.of(rs.getString("element_key")));
                    return null;
                })
                .list();

        return jdbc.sql(
                        """
                        select v."key" as view_key, vv.name, vv.viewpoint
                        from view_version vv
                        join view v on v.id = vv.view_id
                        where v.model_id = :modelId and vv.to_seq is null
                        order by vv.name
                        """)
                .param("modelId", modelId)
                .query((ResultSet rs, int rowNum) -> {
                    Slug key = Slug.of(rs.getString("view_key"));
                    return new View(
                            key, rs.getString("name"), rs.getString("viewpoint"), members.getOrDefault(key, Set.of()));
                })
                .list();
    }

    /** Properties are expanded by PostgreSQL rather than parsed here — {@code jsonb_each_text} is exact and cheap. */
    private Map<Slug, Map<String, String>> loadProperties(UUID modelId, String entity) {
        String sql =
                """
                select owner."key" as owner_key, kv.key as property_key, kv.value as property_value
                from %1$s_version v
                join %1$s owner on owner.id = v.%1$s_id,
                lateral jsonb_each_text(v.properties) kv
                where owner.model_id = :modelId and v.to_seq is null
                """
                        .formatted(entity);
        Map<Slug, Map<String, String>> byOwner = new LinkedHashMap<>();
        jdbc.sql(sql).param("modelId", modelId).query((ResultSet rs, int rowNum) -> {
                    byOwner.computeIfAbsent(Slug.of(rs.getString("owner_key")), key -> new LinkedHashMap<>())
                            .put(rs.getString("property_key"), rs.getString("property_value"));
                    return null;
                })
                .list();
        return byOwner;
    }

    ProfileCatalog loadProfiles(UUID modelId, Metamodel metamodel) {
        ProfileCatalog.Builder builder = ProfileCatalog.builder();
        jdbc.sql("select name, concept_type, attributes from profile where model_id = :modelId order by name")
                .param("modelId", modelId)
                .query((ResultSet rs, int rowNum) -> new Profile(
                        rs.getString("name"), rs.getString("concept_type"), readAttributes(rs.getString("attributes"))))
                .list()
                .forEach(builder::profile);
        return builder.build(metamodel);
    }

    private List<AttributeDefinition> readAttributes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Map<String, String>> entries = json.readValue(raw, new tools.jackson.core.type.TypeReference<>() {});
        return entries.stream()
                .map(entry -> AttributeDefinition.of(
                        entry.get("key"), entry.get("name"), AttributeType.fromCode(entry.get("type"))))
                .toList();
    }

    // ------------------------------------------------------------------ writing

    /**
     * Persists the difference between two graphs as one commit.
     *
     * <p>Takes a row lock on the model, which both allocates the sequence number and serialises
     * concurrent commits — the optimistic check on {@code baseCommitId} is only meaningful if two
     * writers cannot interleave between checking and writing.
     */
    UUID commit(UUID modelId, ModelGraph before, ModelGraph after, String author, String message) {
        long seq = lockAndNextSeq(modelId);
        UUID parentId = head(modelId).commitId();
        UUID commitId = UUID.randomUUID();

        jdbc.sql("insert into model_commit (id, model_id, seq, parent_id, author, message) "
                        + "values (:id, :modelId, :seq, :parentId, :author, :message)")
                .param("id", commitId)
                .param("modelId", modelId)
                .param("seq", seq)
                .param("parentId", parentId)
                .param("author", author)
                .param("message", message)
                .update();

        Map<String, UUID> profileIds = profileIdsByName(modelId);
        writeElements(modelId, before, after, seq, profileIds);
        writeRelationships(modelId, before, after, seq);
        writeViews(modelId, before, after, seq);

        jdbc.sql("update model set head_commit_id = :commitId, updated_at = now() where id = :modelId")
                .param("commitId", commitId)
                .param("modelId", modelId)
                .update();

        return commitId;
    }

    private long lockAndNextSeq(UUID modelId) {
        jdbc.sql("select id from model where id = :modelId for update")
                .param("modelId", modelId)
                .query(UUID.class)
                .single();
        return jdbc.sql("select coalesce(max(seq), 0) + 1 from model_commit where model_id = :modelId")
                .param("modelId", modelId)
                .query(Long.class)
                .single();
    }

    private void writeElements(
            UUID modelId, ModelGraph before, ModelGraph after, long seq, Map<String, UUID> profileIds) {
        Map<Slug, Element> old = byKey(before.elements(), Element::key);
        Map<Slug, Element> current = byKey(after.elements(), Element::key);

        Set<Slug> touched = new LinkedHashSet<>(old.keySet());
        touched.addAll(current.keySet());

        // Every element that survives needs a row before any version can point a parent at it.
        Map<Slug, UUID> ids = elementIds(modelId);
        for (Slug key : current.keySet()) {
            ids.computeIfAbsent(key, missing -> insertElement(modelId, missing));
        }

        for (Slug key : touched) {
            Element previous = old.get(key);
            Element updated = current.get(key);
            if (java.util.Objects.equals(previous, updated)) {
                continue;
            }
            if (previous != null) {
                closeVersion("element", "element_id", ids.get(key), seq);
            }
            if (updated != null) {
                insertElementVersion(ids, updated, seq, profileIds);
            }
        }
    }

    private void writeRelationships(UUID modelId, ModelGraph before, ModelGraph after, long seq) {
        Map<Slug, Relationship> old = byKey(before.relationships(), Relationship::key);
        Map<Slug, Relationship> current = byKey(after.relationships(), Relationship::key);

        Set<Slug> touched = new LinkedHashSet<>(old.keySet());
        touched.addAll(current.keySet());

        Map<Slug, UUID> ids = relationshipIds(modelId);
        Map<Slug, UUID> elementIds = elementIds(modelId);
        for (Slug key : current.keySet()) {
            ids.computeIfAbsent(key, missing -> insertRelationship(modelId, missing));
        }

        for (Slug key : touched) {
            Relationship previous = old.get(key);
            Relationship updated = current.get(key);
            if (java.util.Objects.equals(previous, updated)) {
                continue;
            }
            if (previous != null) {
                closeVersion("relationship", "relationship_id", ids.get(key), seq);
            }
            if (updated != null) {
                jdbc.sql("insert into relationship_version "
                                + "(relationship_id, from_seq, concept_type, source_id, target_id, name, properties) "
                                + "values (:id, :seq, :type, :source, :target, :name, cast(:properties as jsonb))")
                        .param("id", ids.get(key))
                        .param("seq", seq)
                        .param("type", updated.conceptType())
                        .param("source", elementIds.get(updated.source()))
                        .param("target", elementIds.get(updated.target()))
                        .param("name", updated.name())
                        .param("properties", json.writeValueAsString(updated.properties()))
                        .update();
            }
        }
    }

    /**
     * Writes view definitions and membership.
     *
     * <p>Membership is versioned per element rather than as a blob: a diff then shows "added
     * Payment Service to the context view", which is the change that actually happened, instead
     * of "the membership list changed".
     */
    private void writeViews(UUID modelId, ModelGraph before, ModelGraph after, long seq) {
        Map<Slug, View> old = byKey(before.views(), View::key);
        Map<Slug, View> current = byKey(after.views(), View::key);

        Set<Slug> touched = new LinkedHashSet<>(old.keySet());
        touched.addAll(current.keySet());

        Map<Slug, UUID> viewIds = identifiers("view", modelId);
        for (Slug key : current.keySet()) {
            viewIds.computeIfAbsent(key, missing -> {
                UUID id = UUID.randomUUID();
                jdbc.sql("insert into view (id, model_id, \"key\") values (:id, :modelId, :key)")
                        .param("id", id)
                        .param("modelId", modelId)
                        .param("key", missing.value())
                        .update();
                return id;
            });
        }
        Map<Slug, UUID> elementIds = elementIds(modelId);

        for (Slug key : touched) {
            View previous = old.get(key);
            View updated = current.get(key);
            UUID viewId = viewIds.get(key);

            boolean definitionChanged = previous == null
                    || updated == null
                    || !previous.name().equals(updated.name())
                    || !previous.viewpoint().equals(updated.viewpoint());
            if (definitionChanged) {
                if (previous != null) {
                    closeVersion("view", "view_id", viewId, seq);
                }
                if (updated != null) {
                    jdbc.sql("insert into view_version (view_id, from_seq, name, viewpoint) "
                                    + "values (:id, :seq, :name, :viewpoint)")
                            .param("id", viewId)
                            .param("seq", seq)
                            .param("name", updated.name())
                            .param("viewpoint", updated.viewpoint())
                            .update();
                }
            }

            Set<Slug> was = previous == null ? Set.of() : previous.elements();
            Set<Slug> now = updated == null ? Set.of() : updated.elements();

            for (Slug gone : was) {
                if (!now.contains(gone)) {
                    jdbc.sql("update view_element set to_seq = :seq "
                                    + "where view_id = :view and element_id = :element and to_seq is null")
                            .param("seq", seq)
                            .param("view", viewId)
                            .param("element", elementIds.get(gone))
                            .update();
                }
            }
            for (Slug added : now) {
                if (!was.contains(added)) {
                    jdbc.sql("insert into view_element (view_id, element_id, from_seq) "
                                    + "values (:view, :element, :seq)")
                            .param("view", viewId)
                            .param("element", elementIds.get(added))
                            .param("seq", seq)
                            .update();
                }
            }
        }
    }

    private void insertElementVersion(
            Map<Slug, UUID> ids, Element element, long seq, Map<String, UUID> profileIds) {
        jdbc.sql("insert into element_version "
                        + "(element_id, from_seq, concept_type, profile_id, name, documentation, properties, parent_element_id) "
                        + "values (:id, :seq, :type, :profileId, :name, :documentation, cast(:properties as jsonb), :parent)")
                .param("id", ids.get(element.key()))
                .param("seq", seq)
                .param("type", element.conceptType())
                .param("profileId", element.profile().map(profileIds::get).orElse(null))
                .param("name", element.name())
                .param("documentation", element.documentation())
                .param("properties", json.writeValueAsString(element.properties()))
                .param("parent", element.parentKey().map(ids::get).orElse(null))
                .update();
    }

    private void closeVersion(String table, String column, UUID id, long seq) {
        jdbc.sql("update %s_version set to_seq = :seq where %s = :id and to_seq is null".formatted(table, column))
                .param("seq", seq)
                .param("id", id)
                .update();
    }

    private UUID insertElement(UUID modelId, Slug key) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into element (id, model_id, \"key\") values (:id, :modelId, :key)")
                .param("id", id)
                .param("modelId", modelId)
                .param("key", key.value())
                .update();
        return id;
    }

    private UUID insertRelationship(UUID modelId, Slug key) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into relationship (id, model_id, \"key\") values (:id, :modelId, :key)")
                .param("id", id)
                .param("modelId", modelId)
                .param("key", key.value())
                .update();
        return id;
    }

    private Map<Slug, UUID> elementIds(UUID modelId) {
        return identifiers("element", modelId);
    }

    private Map<Slug, UUID> relationshipIds(UUID modelId) {
        return identifiers("relationship", modelId);
    }

    private Map<Slug, UUID> identifiers(String table, UUID modelId) {
        Map<Slug, UUID> ids = new LinkedHashMap<>();
        jdbc.sql("select id, \"key\" from %s where model_id = :modelId".formatted(table))
                .param("modelId", modelId)
                .query((ResultSet rs, int rowNum) -> {
                    ids.put(Slug.of(rs.getString("key")), rs.getObject("id", UUID.class));
                    return null;
                })
                .list();
        return ids;
    }

    private Map<String, UUID> profileIdsByName(UUID modelId) {
        Map<String, UUID> ids = new LinkedHashMap<>();
        jdbc.sql("select id, name from profile where model_id = :modelId")
                .param("modelId", modelId)
                .query((ResultSet rs, int rowNum) -> {
                    ids.put(rs.getString("name"), rs.getObject("id", UUID.class));
                    return null;
                })
                .list();
        return ids;
    }

    private static <T> Map<Slug, T> byKey(java.util.Collection<T> items, java.util.function.Function<T, Slug> key) {
        Map<Slug, T> map = new LinkedHashMap<>();
        items.forEach(item -> map.put(key.apply(item), item));
        return map;
    }

    // ------------------------------------------------------------------ history

    record CommitRow(UUID id, long seq, String author, String message, java.time.Instant createdAt) {}

    List<CommitRow> history(UUID modelId, int limit) {
        return jdbc.sql("select id, seq, author, message, created_at from model_commit "
                        + "where model_id = :modelId order by seq desc limit :limit")
                .param("modelId", modelId)
                .param("limit", limit)
                .query((ResultSet rs, int rowNum) -> new CommitRow(
                        rs.getObject("id", UUID.class),
                        rs.getLong("seq"),
                        rs.getString("author"),
                        rs.getString("message"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    Optional<UUID> resolveCommit(UUID modelId, UUID commitId) {
        return jdbc.sql("select id from model_commit where model_id = :modelId and id = :commitId")
                .param("modelId", modelId)
                .param("commitId", commitId)
                .query(UUID.class)
                .optional();
    }
}
