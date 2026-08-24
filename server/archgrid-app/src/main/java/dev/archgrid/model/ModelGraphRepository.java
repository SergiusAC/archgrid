package dev.archgrid.model;

import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.model.Element;
import dev.archgrid.domain.model.ModelGraph;
import dev.archgrid.domain.model.Relationship;
import dev.archgrid.domain.model.View;
import dev.archgrid.domain.model.ViewFilter;
import dev.archgrid.domain.profile.AttributeDefinition;
import dev.archgrid.domain.profile.AttributeType;
import dev.archgrid.domain.profile.Profile;
import dev.archgrid.domain.profile.ProfileCatalog;
import dev.archgrid.domain.shared.Id;
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
 *
 * <p>The domain's {@link Id} is the primary key (docs/09). There is no second key to translate to
 * and from, so the identity row is written once, when the object first appears, and thereafter
 * only the two columns the uniqueness indexes rest on are kept in step with the current version:
 * an element's or view's {@code name}, a relationship's {@code (concept_type, source_id,
 * target_id)}. Both are cleared when the object is deleted — the identity row outlives it for the
 * history's sake, and a name still on it would block the next thing to take that name.
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
        return load(modelId, null);
    }

    /**
     * The graph as it stood at a commit — the state phase 5 lets an architect look back at.
     *
     * <p>The semantic model only. Layout is not versioned (ADR-0003), so there is no historical
     * arrangement to read and there never will be: dragging a box makes no commit. A caller
     * drawing this on a canvas is drawing today's coordinates around yesterday's model, and has
     * to say so.
     *
     * @param seq the commit's sequence number, not its id — the ranges are over the sequence
     */
    ModelGraph loadAt(UUID modelId, long seq) {
        return load(modelId, seq);
    }

    /**
     * The predicate that decides which version rows are open.
     *
     * <p>Two spellings rather than one general form. HEAD has a partial index behind it ({@code
     * where to_seq is null}) and is the query that runs on every command; a range scan there would
     * pay for history on every write.
     *
     * @param seq null for HEAD
     */
    private static String openAt(String alias, Long seq) {
        return seq == null
                ? alias + ".to_seq is null"
                : "(%1$s.from_seq <= :seq and (%1$s.to_seq is null or %1$s.to_seq > :seq))".formatted(alias);
    }

    private static JdbcClient.StatementSpec at(JdbcClient.StatementSpec statement, Long seq) {
        return seq == null ? statement : statement.param("seq", seq);
    }

    private ModelGraph load(UUID modelId, Long seq) {
        Map<Id, Map<String, String>> elementProperties = loadProperties(modelId, "element", seq);
        Map<Id, Map<String, String>> relationshipProperties = loadProperties(modelId, "relationship", seq);

        List<Element> elements = at(
                        jdbc.sql(
                                        """
                        select e.id as element_id,
                               ev.concept_type,
                               p.name as profile_name,
                               ev.name,
                               ev.documentation
                        from element_version ev
                        join element e on e.id = ev.element_id
                        left join profile p on p.id = ev.profile_id
                        where e.model_id = :modelId and %s
                        order by lower(ev.name), e.id
                        """
                                                .formatted(openAt("ev", seq)))
                                .param("modelId", modelId),
                        seq)
                .query((rs, rowNum) -> {
                    Id key = Id.of(rs.getObject("element_id", UUID.class));
                    return new Element(
                            key,
                            rs.getString("concept_type"),
                            rs.getString("profile_name"),
                            rs.getString("name"),
                            rs.getString("documentation"),
                            elementProperties.getOrDefault(key, Map.of()));
                })
                .list();

        List<Relationship> relationships = at(
                        jdbc.sql(
                                        """
                        select r.id as relationship_id,
                               rv.concept_type,
                               rv.source_id,
                               rv.target_id,
                               rv.name
                        from relationship_version rv
                        join relationship r on r.id = rv.relationship_id
                        where r.model_id = :modelId and %s
                        order by r.id
                        """
                                                .formatted(openAt("rv", seq)))
                                .param("modelId", modelId),
                        seq)
                .query((rs, rowNum) -> {
                    Id key = Id.of(rs.getObject("relationship_id", UUID.class));
                    return new Relationship(
                            key,
                            rs.getString("concept_type"),
                            Id.of(rs.getObject("source_id", UUID.class)),
                            Id.of(rs.getObject("target_id", UUID.class)),
                            rs.getString("name"),
                            relationshipProperties.getOrDefault(key, Map.of()));
                })
                .list();

        return ModelGraph.of(elements, relationships, loadViews(modelId, seq));
    }

    private List<View> loadViews(UUID modelId, Long seq) {
        Map<Id, Set<Id>> members = new LinkedHashMap<>();
        at(
                        jdbc.sql(
                                        """
                        select ve.view_id, ve.element_id
                        from view_element ve
                        join view v on v.id = ve.view_id
                        where v.model_id = :modelId and %s
                        order by ve.element_id
                        """
                                                .formatted(openAt("ve", seq)))
                                .param("modelId", modelId),
                        seq)
                .query((ResultSet rs, int rowNum) -> {
                    members.computeIfAbsent(Id.of(rs.getObject("view_id", UUID.class)), key -> new LinkedHashSet<>())
                            .add(Id.of(rs.getObject("element_id", UUID.class)));
                    return null;
                })
                .list();

        return at(
                        jdbc.sql(
                                        """
                        select v.id as view_id, vv.name, vv.viewpoint, vv.filters::text as filters
                        from view_version vv
                        join view v on v.id = vv.view_id
                        where v.model_id = :modelId and %s
                        order by lower(vv.name), v.id
                        """
                                                .formatted(openAt("vv", seq)))
                                .param("modelId", modelId),
                        seq)
                .query((ResultSet rs, int rowNum) -> {
                    Id key = Id.of(rs.getObject("view_id", UUID.class));
                    return new View(
                            key,
                            rs.getString("name"),
                            rs.getString("viewpoint"),
                            members.getOrDefault(key, Set.of()),
                            readFilters(rs.getString("filters")));
                })
                .list();
    }

    /** Properties are expanded by PostgreSQL rather than parsed here — {@code jsonb_each_text} is exact and cheap. */
    private Map<Id, Map<String, String>> loadProperties(UUID modelId, String entity, Long seq) {
        String sql =
                """
                select owner.id as owner_id, kv.key as property_key, kv.value as property_value
                from %1$s_version v
                join %1$s owner on owner.id = v.%1$s_id,
                lateral jsonb_each_text(v.properties) kv
                where owner.model_id = :modelId and %2$s
                """
                        .formatted(entity, openAt("v", seq));
        Map<Id, Map<String, String>> byOwner = new LinkedHashMap<>();
        at(jdbc.sql(sql).param("modelId", modelId), seq).query((ResultSet rs, int rowNum) -> {
                    byOwner.computeIfAbsent(Id.of(rs.getObject("owner_id", UUID.class)), key -> new LinkedHashMap<>())
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

    /**
     * Adds a profile to a model.
     *
     * <p>Profiles are the customer's own vocabulary — "Microservice is a kind of Application
     * Component" — and they are not versioned by commits: the table has no validity range, and a
     * profile is a statement about the language this model speaks rather than about the
     * architecture it describes (ADR-0002). Which is a wart worth naming: the history cannot say
     * when the vocabulary changed. Versioning it is a migration and a decision, not an oversight
     * to fix in passing.
     */
    void insertProfile(UUID modelId, Profile profile) {
        jdbc.sql("insert into profile (id, model_id, name, concept_type, attributes) "
                        + "values (:id, :modelId, :name, :concept, cast(:attributes as jsonb))")
                .param("id", UUID.randomUUID())
                .param("modelId", modelId)
                .param("name", profile.name())
                .param("concept", profile.conceptType())
                .param("attributes", writeAttributes(profile.attributes()))
                .update();
    }

    /**
     * Removes a profile.
     *
     * <p>The database refuses while any element version — current or historical — is typed by it,
     * through `on delete restrict`. That refusal is the point: an element that lost its profile
     * would be left claiming a type nothing defines.
     */
    int deleteProfile(UUID modelId, String name) {
        return jdbc.sql("delete from profile where model_id = :modelId and name = :name")
                .param("modelId", modelId)
                .param("name", name)
                .update();
    }

    private String writeAttributes(List<AttributeDefinition> attributes) {
        return json.writeValueAsString(attributes.stream()
                .map(attribute -> Map.of(
                        // The slug's value, not the record: Jackson would write `{"value": "..."}`
                        // and the reader expects a string — a round trip that only fails on read.
                        "key", attribute.key().value(),
                        "name", attribute.name(),
                        "type", attribute.type().code()))
                .toList());
    }

    /**
     * A view's filters, which are conditions on what it draws and never on what belongs to it.
     *
     * <p>An entry we cannot make sense of is dropped rather than fatal: a filter is presentation
     * of a decision, and refusing to open a diagram because one condition is unreadable would be
     * the wrong trade.
     */
    private Set<ViewFilter> readFilters(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        List<Map<String, String>> entries = json.readValue(raw, new tools.jackson.core.type.TypeReference<>() {});
        Set<ViewFilter> filters = new java.util.LinkedHashSet<>();
        for (Map<String, String> entry : entries) {
            try {
                filters.add(new ViewFilter(
                        ViewFilter.Kind.of(entry.get("kind")), entry.get("name"), entry.get("value")));
            } catch (RuntimeException ignored) {
                // Unreadable condition; the diagram is worth more than the filter.
            }
        }
        return filters;
    }

    private String writeFilters(Set<ViewFilter> filters) {
        return json.writeValueAsString(filters.stream()
                .map(filter -> {
                    Map<String, String> entry = new java.util.LinkedHashMap<>();
                    entry.put("kind", filter.kind().code());
                    entry.put("name", filter.name());
                    entry.put("value", filter.value());
                    return entry;
                })
                .toList());
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
     * writers cannot interleave between checking and writing. The same lock is what makes the
     * uniqueness rules safe to check in memory: two commits that would each create a 'Kafka' are
     * applied one after the other, and the second sees the first.
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
        Map<Id, Element> old = byKey(before.elements(), Element::key);
        Map<Id, Element> current = byKey(after.elements(), Element::key);

        Set<Id> touched = new LinkedHashSet<>(old.keySet());
        touched.addAll(current.keySet());

        // Every element that survives needs an identity row before a version can point at it. A
        // row may already exist for an element that was deleted and is now back under the same id
        // — an import replaying a file — and that is fine: identity rows are never deleted.
        Set<UUID> known = identityRows("element", modelId);
        for (Id key : current.keySet()) {
            if (!known.contains(key.value())) {
                jdbc.sql("insert into element (id, model_id) values (:id, :modelId)")
                        .param("id", key.value())
                        .param("modelId", modelId)
                        .update();
            }
        }

        // Names first, in two passes: every name that changes hands is given up before any is
        // taken. The unique index is checked statement by statement, and a batch that swaps two
        // names — valid as a whole, which is what the validator judged — would otherwise trip it
        // halfway through.
        for (Id key : touched) {
            Element previous = old.get(key);
            Element updated = current.get(key);
            boolean gaveUpName = previous != null
                    && (updated == null || !ModelGraph.nameKey(previous.name()).equals(ModelGraph.nameKey(updated.name())));
            if (gaveUpName) {
                jdbc.sql("update element set name = null where id = :id")
                        .param("id", key.value())
                        .update();
            }
        }

        for (Id key : touched) {
            Element previous = old.get(key);
            Element updated = current.get(key);
            if (java.util.Objects.equals(previous, updated)) {
                continue;
            }
            if (previous != null) {
                closeVersion("element", "element_id", key.value(), seq);
            }
            if (updated != null) {
                insertElementVersion(updated, seq, profileIds);
                // The identity row's name is the one the uniqueness index sees. A deleted element
                // gave its name up in the first pass, so that the next element may take it.
                jdbc.sql("update element set name = :name where id = :id")
                        .param("name", updated.name())
                        .param("id", key.value())
                        .update();
            }
        }
    }

    private void writeRelationships(UUID modelId, ModelGraph before, ModelGraph after, long seq) {
        Map<Id, Relationship> old = byKey(before.relationships(), Relationship::key);
        Map<Id, Relationship> current = byKey(after.relationships(), Relationship::key);

        Set<Id> touched = new LinkedHashSet<>(old.keySet());
        touched.addAll(current.keySet());

        Set<UUID> known = identityRows("relationship", modelId);
        for (Id key : current.keySet()) {
            if (!known.contains(key.value())) {
                jdbc.sql("insert into relationship (id, model_id) values (:id, :modelId)")
                        .param("id", key.value())
                        .param("modelId", modelId)
                        .update();
            }
        }

        for (Id key : touched) {
            Relationship previous = old.get(key);
            Relationship updated = current.get(key);
            if (java.util.Objects.equals(previous, updated)) {
                continue;
            }
            if (previous != null) {
                closeVersion("relationship", "relationship_id", key.value(), seq);
            }
            if (updated != null) {
                jdbc.sql("insert into relationship_version "
                                + "(relationship_id, from_seq, concept_type, source_id, target_id, name, properties) "
                                + "values (:id, :seq, :type, :source, :target, :name, cast(:properties as jsonb))")
                        .param("id", key.value())
                        .param("seq", seq)
                        .param("type", updated.conceptType())
                        .param("source", updated.source().value())
                        .param("target", updated.target().value())
                        .param("name", updated.name())
                        .param("properties", json.writeValueAsString(updated.properties()))
                        .update();
            }
            // The triple on the identity row is what the uniqueness index sees. Type and ends never
            // change while a relationship lives, so this only ever sets them on arrival and clears
            // them on deletion — freeing the pair for the next relationship of that type.
            jdbc.sql("update relationship set concept_type = :type, source_id = :source, target_id = :target "
                            + "where id = :id")
                    .param("type", updated == null ? null : updated.conceptType())
                    .param("source", updated == null ? null : updated.source().value())
                    .param("target", updated == null ? null : updated.target().value())
                    .param("id", key.value())
                    .update();
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
        Map<Id, View> old = byKey(before.views(), View::key);
        Map<Id, View> current = byKey(after.views(), View::key);

        Set<Id> touched = new LinkedHashSet<>(old.keySet());
        touched.addAll(current.keySet());

        Set<UUID> known = identityRows("view", modelId);
        for (Id key : current.keySet()) {
            if (!known.contains(key.value())) {
                jdbc.sql("insert into view (id, model_id) values (:id, :modelId)")
                        .param("id", key.value())
                        .param("modelId", modelId)
                        .update();
            }
        }

        // Names given up before any is taken, for the same reason as for elements: a swap is valid
        // as a whole and the index judges one statement at a time.
        for (Id key : touched) {
            View previous = old.get(key);
            View updated = current.get(key);
            boolean gaveUpName = previous != null
                    && (updated == null || !ModelGraph.nameKey(previous.name()).equals(ModelGraph.nameKey(updated.name())));
            if (gaveUpName) {
                jdbc.sql("update view set name = null where id = :id")
                        .param("id", key.value())
                        .update();
            }
        }

        for (Id key : touched) {
            View previous = old.get(key);
            View updated = current.get(key);
            UUID viewId = key.value();

            // Filters are part of the definition, not of the membership: "this diagram is about
            // the technology layer" is versioned exactly like its name (ADR-0003). Leaving them
            // out of this comparison wrote the commit and none of the change.
            boolean definitionChanged = previous == null
                    || updated == null
                    || !previous.name().equals(updated.name())
                    || !previous.viewpoint().equals(updated.viewpoint())
                    || !previous.filters().equals(updated.filters());
            if (definitionChanged) {
                if (previous != null) {
                    closeVersion("view", "view_id", viewId, seq);
                }
                if (updated != null) {
                    jdbc.sql("insert into view_version (view_id, from_seq, name, viewpoint, filters) "
                                    + "values (:id, :seq, :name, :viewpoint, cast(:filters as jsonb))")
                            .param("id", viewId)
                            .param("seq", seq)
                            .param("name", updated.name())
                            .param("viewpoint", updated.viewpoint())
                            .param("filters", writeFilters(updated.filters()))
                            .update();
                }
                if (updated != null) {
                    jdbc.sql("update view set name = :name where id = :id")
                            .param("name", updated.name())
                            .param("id", viewId)
                            .update();
                }
            }

            Set<Id> was = previous == null ? Set.of() : previous.elements();
            Set<Id> now = updated == null ? Set.of() : updated.elements();

            for (Id gone : was) {
                if (!now.contains(gone)) {
                    jdbc.sql("update view_element set to_seq = :seq "
                                    + "where view_id = :view and element_id = :element and to_seq is null")
                            .param("seq", seq)
                            .param("view", viewId)
                            .param("element", gone.value())
                            .update();
                }
            }
            for (Id added : now) {
                if (!was.contains(added)) {
                    jdbc.sql("insert into view_element (view_id, element_id, from_seq) "
                                    + "values (:view, :element, :seq)")
                            .param("view", viewId)
                            .param("element", added.value())
                            .param("seq", seq)
                            .update();
                }
            }
        }
    }

    private void insertElementVersion(Element element, long seq, Map<String, UUID> profileIds) {
        jdbc.sql("insert into element_version "
                        + "(element_id, from_seq, concept_type, profile_id, name, documentation, properties) "
                        + "values (:id, :seq, :type, :profileId, :name, :documentation, cast(:properties as jsonb))")
                .param("id", element.key().value())
                .param("seq", seq)
                .param("type", element.conceptType())
                .param("profileId", element.profile().map(profileIds::get).orElse(null))
                .param("name", element.name())
                .param("documentation", element.documentation())
                .param("properties", json.writeValueAsString(element.properties()))
                .update();
    }

    private void closeVersion(String table, String column, UUID id, long seq) {
        jdbc.sql("update %s_version set to_seq = :seq where %s = :id and to_seq is null".formatted(table, column))
                .param("seq", seq)
                .param("id", id)
                .update();
    }

    /** Every identity row of the model — current and deleted alike, since identity rows are never removed. */
    private Set<UUID> identityRows(String table, UUID modelId) {
        Set<UUID> ids = new LinkedHashSet<>();
        jdbc.sql("select id from %s where model_id = :modelId".formatted(table))
                .param("modelId", modelId)
                .query((ResultSet rs, int rowNum) -> {
                    ids.add(rs.getObject("id", UUID.class));
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

    private static <T> Map<Id, T> byKey(java.util.Collection<T> items, java.util.function.Function<T, Id> key) {
        Map<Id, T> map = new LinkedHashMap<>();
        items.forEach(item -> map.put(key.apply(item), item));
        return map;
    }

    // ------------------------------------------------------------------ history

    /**
     * One commit.
     *
     * @param parentId null for the first commit of a repository, whose parent is the empty graph
     */
    record CommitRow(UUID id, long seq, UUID parentId, String author, String message, java.time.Instant createdAt) {}

    private static final String COMMIT_COLUMNS = "id, seq, parent_id, author, message, created_at";

    private static CommitRow readCommit(ResultSet rs) throws java.sql.SQLException {
        return new CommitRow(
                rs.getObject("id", UUID.class),
                rs.getLong("seq"),
                rs.getObject("parent_id", UUID.class),
                rs.getString("author"),
                rs.getString("message"),
                rs.getTimestamp("created_at").toInstant());
    }

    List<CommitRow> history(UUID modelId, int limit) {
        return jdbc.sql("select " + COMMIT_COLUMNS + " from model_commit "
                        + "where model_id = :modelId order by seq desc limit :limit")
                .param("modelId", modelId)
                .param("limit", limit)
                .query((ResultSet rs, int rowNum) -> readCommit(rs))
                .list();
    }

    /** Scoped to the model on purpose: a commit id from another workspace must read as absent, not as found. */
    Optional<CommitRow> commit(UUID modelId, UUID commitId) {
        return jdbc.sql("select " + COMMIT_COLUMNS + " from model_commit "
                        + "where model_id = :modelId and id = :commitId")
                .param("modelId", modelId)
                .param("commitId", commitId)
                .query((ResultSet rs, int rowNum) -> readCommit(rs))
                .optional();
    }
}
