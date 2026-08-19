package db.migration;

import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.metamodel.MetamodelLoader;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Containment stops being a field on the element and becomes what it always was: a relationship.
 *
 * <p>ADR-0011 decided this and only half of it shipped. The canvas stopped writing {@code
 * element.parent} when nesting became a property of one view, while {@code RelationshipDerivation}
 * went on reading the field as composition — so derivation leaned on a column nothing filled any
 * more, and every diagram that expressed containment by nesting derived from a stale answer.
 *
 * <p>Java rather than SQL for one reason: <strong>the matrix</strong>. {@code move-element} never
 * checked containment against the metamodel — that is the hole the field opened by living outside
 * the relationship table — so real data holds parents between concepts ArchiMate does not allow to
 * compose. Turning those into Composition rows produces a repository that fails validation on
 * every later command: one impossible claim, and nothing can be edited again. So each pair is
 * checked, and what the language forbids is dropped with a line in the log rather than written
 * down as fact. Nesting on views is untouched — that lives in {@code view_layout_node} and is
 * presentation either way (ADR-0011).
 *
 * <p>The package is Flyway's own {@code db.migration} rather than {@code dev.archgrid.*}: this is
 * a one-off script that happens to be written in Java, and it has no business appearing in the
 * slice graph the architecture tests police.
 *
 * <p>What this costs, stated plainly: only the current state is converted. Historical element
 * versions lose their parent with the column, so a model read at a commit made before this
 * migration shows no containment. Reconstructing a relationship version for every range a parent
 * ever had is a gaps-and-islands rewrite of the whole table, for data that at this point in the
 * product's life exists only on developer machines. The history UI arrives in phase 5; this is the
 * last cheap moment.
 */
public class V10__Containment_becomes_composition extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V10__Containment_becomes_composition.class);

    private static final String METAMODEL = "metamodel/archimate-3.2.yaml";

    @Override
    public void migrate(Context context) throws Exception {
        Metamodel metamodel = loadMetamodel();
        Connection connection = context.getConnection();

        int written = 0;
        int dropped = 0;
        for (Containment containment : read(connection)) {
            if (!metamodel.check(containment.parentType(), containment.childType(), "Composition")
                    .isPermitted()) {
                // Never valid ArchiMate; it existed because the field escaped the check every
                // relationship goes through. Recorded here and dropped, rather than migrated into
                // a model that would refuse every later command because of it.
                log.warn(
                        "Dropping containment of '{}' by '{}': Composition is not permitted from {} to {}",
                        containment.childKey(),
                        containment.parentKey(),
                        containment.parentType(),
                        containment.childType());
                dropped++;
                continue;
            }
            write(connection, containment);
            written++;
        }
        log.info(
                "Containment became composition: {} relationships written, {} dropped as impermissible",
                written,
                dropped);

        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table element_version drop column parent_element_id");
            statement.execute("comment on table relationship is "
                    + "'Every relationship, containment included: nesting on a diagram is presentation "
                    + "of one of these (ADR-0011).'");
        }
    }

    private static Metamodel loadMetamodel() throws Exception {
        try (InputStream source =
                V10__Containment_becomes_composition.class.getClassLoader().getResourceAsStream(METAMODEL)) {
            if (source == null) {
                // Fatal on purpose. Guessing which containments are legal is the one thing this
                // migration exists not to do.
                throw new IllegalStateException("could not read " + METAMODEL + " from the classpath");
            }
            return MetamodelLoader.load(source);
        }
    }

    /**
     * Current containments not already stated as a relationship.
     *
     * <p>The editor has been creating structural relationships on nesting since ADR-0011 shipped,
     * and a second row between the same pair would say the same thing twice.
     */
    private static List<Containment> read(Connection connection) throws Exception {
        String sql =
                """
                select ev.element_id as child_id, ev.parent_element_id as parent_id,
                       child."key" as child_key, parent."key" as parent_key,
                       ev.concept_type as child_type, pv.concept_type as parent_type,
                       child.model_id as model_id, c.seq as head_seq
                from element_version ev
                join element child on child.id = ev.element_id
                join element parent on parent.id = ev.parent_element_id
                join element_version pv on pv.element_id = parent.id and pv.to_seq is null
                join model m on m.id = child.model_id
                join model_commit c on c.id = m.head_commit_id
                where ev.to_seq is null
                  and ev.parent_element_id is not null
                  and not exists (
                      select 1 from relationship_version rv
                      where rv.to_seq is null
                        and rv.source_id = ev.parent_element_id
                        and rv.target_id = ev.element_id
                        and rv.concept_type in ('Composition', 'Aggregation', 'Assignment', 'Realization')
                  )
                """;
        List<Containment> found = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                found.add(new Containment(
                        UUID.fromString(rows.getString("parent_id")),
                        UUID.fromString(rows.getString("child_id")),
                        rows.getString("parent_key"),
                        rows.getString("child_key"),
                        rows.getString("parent_type"),
                        rows.getString("child_type"),
                        UUID.fromString(rows.getString("model_id")),
                        rows.getLong("head_seq")));
            }
        }
        return found;
    }

    private static void write(Connection connection, Containment containment) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement =
                connection.prepareStatement("insert into relationship (id, model_id, \"key\") values (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, containment.modelId());
            statement.setString(3, key(connection, containment));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into relationship_version "
                        + "(relationship_id, from_seq, to_seq, concept_type, source_id, target_id) "
                        + "values (?, ?, null, 'Composition', ?, ?)")) {
            statement.setObject(1, id);
            statement.setLong(2, containment.headSeq());
            statement.setObject(3, containment.parentId());
            statement.setObject(4, containment.childId());
            statement.executeUpdate();
        }
    }

    /**
     * Readable where it fits, hashed where it does not.
     *
     * <p>A slug is what the command log, the future DSL and every URL will show, so {@code
     * payment-system-composition-payment-api} earns its length; a truncation colliding with a real
     * key would not.
     */
    private static String key(Connection connection, Containment containment) throws Exception {
        String readable = containment.parentKey() + "-composition-" + containment.childKey();
        if (readable.length() <= 64 && !taken(connection, containment.modelId(), readable)) {
            return readable;
        }
        String hashed = "composition-"
                + Integer.toHexString(
                        (containment.parentId().toString() + containment.childId()).hashCode() & 0x7fffffff);
        return taken(connection, containment.modelId(), hashed)
                ? hashed + "-" + Integer.toHexString(containment.childId().hashCode() & 0x7fffffff)
                : hashed;
    }

    private static boolean taken(Connection connection, UUID modelId, String key) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement("select 1 from relationship where model_id = ? and \"key\" = ?")) {
            statement.setObject(1, modelId);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private record Containment(
            UUID parentId,
            UUID childId,
            String parentKey,
            String childKey,
            String parentType,
            String childType,
            UUID modelId,
            long headSeq) {}
}
