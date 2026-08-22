package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One identity per object, step two of three: the data is made to obey the rules V18 will enforce.
 *
 * <p>Until now two elements could share a name and two relationships could say the same thing —
 * the editor's keys differed, so nothing objected. V18's unique indexes refuse both, and an index
 * cannot be created over data that violates it. So, per model: a second holder of a name is
 * renamed {@code Name (2)}, {@code (3)}…, and a second relationship of one type between the same
 * two elements is deleted, its lines re-pointed at the one that stays.
 *
 * <p>Java rather than SQL because this is a change to user content and owes the history a commit:
 * each affected model gets one, authored by the migration, so that "why is my element called
 * Kafka (2)" has an answer in the log rather than a mystery in the data. The first holder of a name
 * — the earliest version — keeps it, on the reasoning that the later one was the accident.
 *
 * <p>The package is Flyway's own {@code db.migration} rather than {@code dev.archgrid.*}: a one-off
 * script that happens to be written in Java has no business in the slice graph the architecture
 * tests police (precedent: V10).
 */
public class V17__Names_and_triples_made_unique extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V17__Names_and_triples_made_unique.class);

    private static final String AUTHOR = "migration:V17";
    private static final int MAX_NAME_LENGTH = 200;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        int renamedElements = 0;
        int renamedViews = 0;
        int droppedRelationships = 0;

        for (UUID modelId : models(connection)) {
            List<Rename> elementRenames = renames(connection, modelId, "element");
            List<Rename> viewRenames = renames(connection, modelId, "view");
            List<Duplicate> duplicates = duplicateTriples(connection, modelId);
            if (elementRenames.isEmpty() && viewRenames.isEmpty() && duplicates.isEmpty()) {
                continue;
            }

            long seq = openCommit(connection, modelId, elementRenames.size() + viewRenames.size(), duplicates.size());
            for (Rename rename : elementRenames) {
                renameElement(connection, rename, seq);
                log.warn("Renamed element '{}' to '{}' in model {}: the name was held twice", rename.was(), rename.now(), modelId);
            }
            for (Rename rename : viewRenames) {
                renameView(connection, rename, seq);
                log.warn("Renamed view '{}' to '{}' in model {}: the name was held twice", rename.was(), rename.now(), modelId);
            }
            for (Duplicate duplicate : duplicates) {
                dropRelationship(connection, duplicate, seq);
                log.warn("Dropped relationship {} in model {}: it restated {}", duplicate.dropped(), modelId, duplicate.kept());
            }
            renamedElements += elementRenames.size();
            renamedViews += viewRenames.size();
            droppedRelationships += duplicates.size();
        }
        log.info(
                "Names and triples made unique: {} elements renamed, {} views renamed, {} relationships dropped",
                renamedElements,
                renamedViews,
                droppedRelationships);
    }

    // ------------------------------------------------------------------ what to change

    private static List<UUID> models(Connection connection) throws Exception {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("select id from model order by id");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ids.add(UUID.fromString(rows.getString("id")));
            }
        }
        return ids;
    }

    /**
     * Which current names of {@code table} are held more than once, and what each later holder
     * becomes. Earliest version first keeps the name; the rest take the smallest free suffix.
     */
    private static List<Rename> renames(Connection connection, UUID modelId, String table) throws Exception {
        // Every current name, earliest first, so the first holder is the one that keeps it.
        String sql = """
                select o.id, o.name, v.from_seq
                from %1$s o
                join %1$s_version v on v.%1$s_id = o.id and v.to_seq is null
                where o.model_id = ? and o.name is not null
                order by v.from_seq, o.id
                """.formatted(table);
        Set<String> taken = new LinkedHashSet<>();
        Map<String, List<Holder>> byName = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, modelId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("name");
                    taken.add(key(name));
                    byName.computeIfAbsent(key(name), k -> new ArrayList<>())
                            .add(new Holder(UUID.fromString(rows.getString("id")), name, rows.getLong("from_seq")));
                }
            }
        }
        List<Rename> renames = new ArrayList<>();
        for (List<Holder> holders : byName.values()) {
            for (Holder later : holders.subList(1, holders.size())) {
                String fresh = freeName(later.name(), taken);
                taken.add(key(fresh));
                renames.add(new Rename(later.id(), later.name(), fresh, later.fromSeq()));
            }
        }
        return renames;
    }

    /** Relationships that restate an earlier one: same type, same two ends, at HEAD. */
    private static List<Duplicate> duplicateTriples(Connection connection, UUID modelId) throws Exception {
        String sql = """
                select r.id, r.concept_type, r.source_id, r.target_id, v.from_seq
                from relationship r
                join relationship_version v on v.relationship_id = r.id and v.to_seq is null
                where r.model_id = ? and r.source_id is not null
                order by v.from_seq, r.id
                """;
        Map<String, UUID> kept = new LinkedHashMap<>();
        List<Duplicate> duplicates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, modelId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID id = UUID.fromString(rows.getString("id"));
                    String triple = rows.getString("concept_type") + "|" + rows.getString("source_id") + "|"
                            + rows.getString("target_id");
                    UUID first = kept.putIfAbsent(triple, id);
                    if (first != null) {
                        duplicates.add(new Duplicate(id, first, rows.getLong("from_seq")));
                    }
                }
            }
        }
        return duplicates;
    }

    // ------------------------------------------------------------------ the commit

    private static long openCommit(Connection connection, UUID modelId, int renamed, int dropped) throws Exception {
        long seq;
        try (PreparedStatement statement =
                connection.prepareStatement("select coalesce(max(seq), 0) + 1 from model_commit where model_id = ?")) {
            statement.setObject(1, modelId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                seq = rows.getLong(1);
            }
        }
        UUID parent = null;
        try (PreparedStatement statement = connection.prepareStatement("select head_commit_id from model where id = ?")) {
            statement.setObject(1, modelId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next() && rows.getString(1) != null) {
                    parent = UUID.fromString(rows.getString(1));
                }
            }
        }
        UUID commitId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into model_commit (id, model_id, seq, parent_id, author, message) values (?, ?, ?, ?, ?, ?)")) {
            statement.setObject(1, commitId);
            statement.setObject(2, modelId);
            statement.setLong(3, seq);
            statement.setObject(4, parent);
            statement.setString(5, AUTHOR);
            statement.setString(6, "Names made unique: " + renamed + " renamed, " + dropped
                    + " duplicate relationships removed (migration V17, docs/09)");
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                connection.prepareStatement("update model set head_commit_id = ?, updated_at = now() where id = ?")) {
            statement.setObject(1, commitId);
            statement.setObject(2, modelId);
            statement.executeUpdate();
        }
        return seq;
    }

    private static void renameElement(Connection connection, Rename rename, long seq) throws Exception {
        closeVersion(connection, "element", rename.id(), seq);
        try (PreparedStatement statement = connection.prepareStatement(
                """
                insert into element_version (element_id, from_seq, concept_type, profile_id, name, documentation, properties)
                select element_id, ?, concept_type, profile_id, ?, documentation, properties
                from element_version where element_id = ? and to_seq = ?
                """)) {
            statement.setLong(1, seq);
            statement.setString(2, rename.now());
            statement.setObject(3, rename.id());
            statement.setLong(4, seq);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("update element set name = ? where id = ?")) {
            statement.setString(1, rename.now());
            statement.setObject(2, rename.id());
            statement.executeUpdate();
        }
    }

    private static void renameView(Connection connection, Rename rename, long seq) throws Exception {
        closeVersion(connection, "view", rename.id(), seq);
        try (PreparedStatement statement = connection.prepareStatement(
                """
                insert into view_version (view_id, from_seq, name, viewpoint, filters)
                select view_id, ?, ?, viewpoint, filters
                from view_version where view_id = ? and to_seq = ?
                """)) {
            statement.setLong(1, seq);
            statement.setString(2, rename.now());
            statement.setObject(3, rename.id());
            statement.setLong(4, seq);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("update view set name = ? where id = ?")) {
            statement.setString(1, rename.now());
            statement.setObject(2, rename.id());
            statement.executeUpdate();
        }
    }

    /**
     * Deletes the later relationship the way the pipeline would: its version closes, its triple
     * clears. Its lines and its hidings go to the survivor rather than vanishing — the diagrams
     * drew that statement, and the statement is still made.
     */
    private static void dropRelationship(Connection connection, Duplicate duplicate, long seq) throws Exception {
        closeVersion(connection, "relationship", duplicate.dropped(), seq);
        try (PreparedStatement statement = connection.prepareStatement(
                "update relationship set concept_type = null, source_id = null, target_id = null where id = ?")) {
            statement.setObject(1, duplicate.dropped());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "update view_layout_link set relationship_id = ?, link_key = ? where relationship_id = ?")) {
            statement.setObject(1, duplicate.kept());
            statement.setString(2, duplicate.kept().toString());
            statement.setObject(3, duplicate.dropped());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                insert into view_hidden_relationship (view_id, relationship_id)
                select view_id, ? from view_hidden_relationship where relationship_id = ?
                on conflict do nothing
                """)) {
            statement.setObject(1, duplicate.kept());
            statement.setObject(2, duplicate.dropped());
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                connection.prepareStatement("delete from view_hidden_relationship where relationship_id = ?")) {
            statement.setObject(1, duplicate.dropped());
            statement.executeUpdate();
        }
    }

    private static void closeVersion(Connection connection, String table, UUID id, long seq) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "update %s_version set to_seq = ? where %s_id = ? and to_seq is null".formatted(table, table))) {
            statement.setLong(1, seq);
            statement.setObject(2, id);
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ names

    /** The same comparison as {@code ModelGraph.nameKey} and the V18 index: case-insensitive, trimmed. */
    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static String freeName(String name, Set<String> taken) {
        for (int suffix = 2; ; suffix++) {
            String tail = " (" + suffix + ")";
            String base = name.length() + tail.length() > MAX_NAME_LENGTH
                    ? name.substring(0, MAX_NAME_LENGTH - tail.length()).stripTrailing()
                    : name;
            String candidate = base + tail;
            if (!taken.contains(key(candidate))) {
                return candidate;
            }
        }
    }

    private record Holder(UUID id, String name, long fromSeq) {}

    private record Rename(UUID id, String was, String now, long fromSeq) {}

    private record Duplicate(UUID dropped, UUID kept, long fromSeq) {}
}
