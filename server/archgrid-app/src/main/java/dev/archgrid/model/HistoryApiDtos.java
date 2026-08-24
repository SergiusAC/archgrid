package dev.archgrid.model;

import dev.archgrid.domain.model.GraphDiff;
import dev.archgrid.domain.model.ModelGraph;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The wire contract for the change log.
 *
 * <p>Ids on this surface are ids (docs/09), but the diff carries <em>names</em> for everything it
 * mentions — an endpoint, an element that joined a view — resolved as of the state it was read
 * from. A history that answers in UUIDs is a history nobody reads, and resolving them client-side
 * is impossible for the objects a commit deleted.
 */
final class HistoryApiDtos {

    private HistoryApiDtos() {}

    /**
     * How much of a value the diff prints before it gives up.
     *
     * <p>Documentation is the one attribute with no upper bound to speak of, and a commit that
     * rewrote ten of them would otherwise put a chapter on the wire. Truncation is visible: the
     * ellipsis says the value goes on, which is enough for a reader deciding whether to open it.
     */
    private static final int MAX_VALUE = 400;

    record CommitEntry(UUID id, long seq, UUID parentId, String author, String message, Instant createdAt) {

        static CommitEntry from(ModelGraphRepository.CommitRow row) {
            return row == null
                    ? null
                    : new CommitEntry(
                            row.id(), row.seq(), row.parentId(), row.author(), row.message(), row.createdAt());
        }
    }

    record SummaryResponse(int added, int removed, int changed) {

        static SummaryResponse from(GraphDiff.Summary summary) {
            return new SummaryResponse(summary.added(), summary.removed(), summary.changed());
        }
    }

    record FieldChangeResponse(String field, String before, String after) {

        static FieldChangeResponse from(GraphDiff.FieldChange change) {
            return new FieldChangeResponse(change.field(), abbreviate(change.before()), abbreviate(change.after()));
        }
    }

    record ChangeResponse(
            String subject,
            String verb,
            String key,
            String type,
            String label,
            String source,
            String target,
            List<FieldChangeResponse> fields) {

        static ChangeResponse from(GraphDiff.Change change) {
            return new ChangeResponse(
                    change.subject().code(),
                    change.verb().code(),
                    change.key().toString(),
                    change.type(),
                    change.label(),
                    change.source(),
                    change.target(),
                    change.fields().stream().map(FieldChangeResponse::from).toList());
        }
    }

    /**
     * @param from null when {@code to} is the repository's first commit and there is no earlier
     *     state to name
     */
    record DiffResponse(
            CommitEntry from, CommitEntry to, SummaryResponse summary, List<ChangeResponse> changes) {

        static DiffResponse from(HistoryService.Comparison comparison) {
            return new DiffResponse(
                    CommitEntry.from(comparison.from()),
                    CommitEntry.from(comparison.to()),
                    SummaryResponse.from(comparison.diff().summary()),
                    comparison.diff().changes().stream()
                            .map(ChangeResponse::from)
                            .toList());
        }
    }

    /**
     * The repository as it stood, read-only.
     *
     * <p>Views come back as summaries with no layout, because there is none to send: coordinates
     * are not versioned (ADR-0003) and the ones on file are today's. A client that draws this is
     * drawing an old model in a current arrangement and owes the user that sentence.
     */
    record SnapshotResponse(
            CommitEntry commit,
            List<ModelApiDtos.ElementResponse> elements,
            List<ModelApiDtos.RelationshipResponse> relationships,
            List<ModelApiDtos.ViewSummaryResponse> views) {

        static SnapshotResponse from(HistoryService.Snapshot snapshot) {
            ModelGraph graph = snapshot.graph();
            return new SnapshotResponse(
                    CommitEntry.from(snapshot.commit()),
                    graph.elements().stream()
                            .map(ModelApiDtos.ElementResponse::from)
                            .toList(),
                    graph.relationships().stream()
                            .map(ModelApiDtos.RelationshipResponse::from)
                            .toList(),
                    graph.views().stream()
                            // Folders are unversioned organisation (ADR-0009), so a snapshot has
                            // nothing to say about where a view was filed at the time.
                            .map(view -> ModelApiDtos.ViewSummaryResponse.from(view, null))
                            .toList());
        }
    }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= MAX_VALUE) {
            return value;
        }
        return value.substring(0, MAX_VALUE) + "…";
    }
}
