package dev.archgrid.model;

import dev.archgrid.domain.model.GraphDiff;
import dev.archgrid.domain.model.ModelGraph;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The change log, read.
 *
 * <p>In the model slice rather than in a slice of its own, and that is a decision rather than an
 * oversight. History is the version rows this package already writes, read back through a
 * different predicate; a {@code history} package would either restate that SQL or need a gateway
 * onto the graph repository wide enough to make the repository public in all but name — and the
 * one thing {@link ModelGraphRepository} being package-private buys is that nothing outside can
 * write to the graph except through {@link ModelCommandService}.
 *
 * <p>Reads only. Every method here is {@code readOnly}, and there is deliberately no revert: an
 * undo is a new commit built from a diff, which is a command like any other and belongs on the
 * one mutation path (ADR-0004).
 */
@Service
public class HistoryService {

    private final ModelGraphRepository graphs;

    HistoryService(ModelGraphRepository graphs) {
        this.graphs = graphs;
    }

    /** Raised when a commit id does not name a commit of this repository. The controller's 404. */
    public static class UnknownCommitException extends RuntimeException {

        private final transient UUID commitId;

        UnknownCommitException(UUID commitId) {
            super("There is no commit " + commitId + " in this repository.");
            this.commitId = commitId;
        }

        public UUID commitId() {
            return commitId;
        }
    }

    @Transactional(readOnly = true)
    public List<ModelGraphRepository.CommitRow> commits(UUID modelId, int limit) {
        return graphs.history(modelId, limit);
    }

    /**
     * The repository as it stood at a commit.
     *
     * <p>The model, not the diagram. Layout is unversioned by design (ADR-0003), so there is no
     * arrangement to restore alongside it — which is why this comes back as a graph to read rather
     * than as a view to open.
     */
    public record Snapshot(ModelGraphRepository.CommitRow commit, ModelGraph graph) {}

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID modelId, UUID commitId) {
        ModelGraphRepository.CommitRow commit = require(modelId, commitId);
        return new Snapshot(commit, graphs.loadAt(modelId, commit.seq()));
    }

    /**
     * Two states and what separates them.
     *
     * @param from null when {@code to} is the first commit of the repository — there is no earlier
     *     state to name, and everything in it reads as added
     */
    public record Comparison(
            ModelGraphRepository.CommitRow from, ModelGraphRepository.CommitRow to, GraphDiff diff) {}

    /**
     * Compares two commits, or a commit with the one before it.
     *
     * <p>A null {@code fromCommitId} means "what did this commit do", which is the question asked
     * of a log entry and so the default. Naming a later commit as {@code from} is legal and yields
     * the inverse — the history is linear, and neither direction is more true than the other.
     */
    @Transactional(readOnly = true)
    public Comparison diff(UUID modelId, UUID fromCommitId, UUID toCommitId) {
        ModelGraphRepository.CommitRow to = require(modelId, toCommitId);
        UUID baseId = fromCommitId != null ? fromCommitId : to.parentId();
        ModelGraphRepository.CommitRow from = baseId == null ? null : require(modelId, baseId);

        // The first commit's predecessor is the empty repository, not commit zero: there is no row
        // to read a state from, and every object in that commit genuinely appeared in it.
        ModelGraph before = from == null ? ModelGraph.empty() : graphs.loadAt(modelId, from.seq());
        ModelGraph after = graphs.loadAt(modelId, to.seq());
        return new Comparison(from, to, GraphDiff.between(before, after));
    }

    private ModelGraphRepository.CommitRow require(UUID modelId, UUID commitId) {
        return graphs.commit(modelId, commitId).orElseThrow(() -> new UnknownCommitException(commitId));
    }
}
