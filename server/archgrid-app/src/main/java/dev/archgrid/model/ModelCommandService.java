package dev.archgrid.model;

import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.model.ModelChange;
import dev.archgrid.domain.model.ModelCommand;
import dev.archgrid.domain.model.ModelGraph;
import dev.archgrid.domain.model.Violation;
import dev.archgrid.domain.profile.Profile;
import dev.archgrid.domain.profile.ProfileCatalog;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single mutation path for the semantic model (ADR-0004).
 *
 * <pre>
 *   optimistic check on baseCommitId
 *     -> apply commands to an in-memory snapshot
 *       -> validate against the metamodel
 *         -> transactional commit
 * </pre>
 *
 * <p>Authorisation happens at the controller, persistence in the repository, and the rules in
 * the domain. What lives here is only the order in which those happen, which is why it is short
 * — and it must stay the only place that writes to the graph.
 */
@Service
public class ModelCommandService {

    private final ModelGraphRepository graphs;
    private final Metamodel metamodel;

    ModelCommandService(ModelGraphRepository graphs, Metamodel metamodel) {
        this.graphs = graphs;
        this.metamodel = metamodel;
    }

    /** Raised when the caller's {@code baseCommitId} is not the current head. */
    public static class StaleBaseCommitException extends RuntimeException {

        private final transient UUID expected;
        private final transient UUID actual;

        StaleBaseCommitException(UUID expected, UUID actual) {
            super("The model has moved on since this change was prepared. Expected base commit " + expected
                    + ", but the current head is " + actual + ".");
            this.expected = expected;
            this.actual = actual;
        }

        public UUID expected() {
            return expected;
        }

        public UUID actual() {
            return actual;
        }
    }

    /** Raised when the change would leave the model invalid. Carries every reason, not the first. */
    public static class ModelRejectedException extends RuntimeException {

        private final transient List<Violation> violations;

        ModelRejectedException(List<Violation> violations) {
            super("The change was refused: " + violations.size() + " problem(s).");
            this.violations = List.copyOf(violations);
        }

        public List<Violation> violations() {
            return violations;
        }
    }

    public record CommitResult(UUID commitId, int elements, int relationships) {}

    @Transactional
    public CommitResult apply(UUID modelId, UUID baseCommitId, List<ModelCommand> commands, String author, String message) {
        ModelGraphRepository.Head head = graphs.head(modelId);
        if (!java.util.Objects.equals(baseCommitId, head.commitId())) {
            throw new StaleBaseCommitException(baseCommitId, head.commitId());
        }

        ModelGraph before = graphs.loadCurrent(modelId);
        ProfileCatalog profiles = graphs.loadProfiles(modelId, metamodel);

        ModelChange.Result result = ModelChange.apply(before, commands, metamodel, profiles);
        if (!result.isSuccess()) {
            // Nothing has been written yet, so throwing is enough to discard the whole batch.
            // A partially applied change is never a valid outcome here.
            throw new ModelRejectedException(result.violations());
        }

        ModelGraph after = result.graph();
        UUID commitId = graphs.commit(modelId, before, after, author, message);
        return new CommitResult(commitId, after.elements().size(), after.relationships().size());
    }

    /**
     * What an import produced: the commit, what it <em>added</em>, and everything it threw away.
     *
     * <p>Added rather than the graph's totals, and the difference matters the moment a repository
     * is not empty: "1248 elements" after importing a file of three says nothing about the file.
     * An import report is about the import.
     */
    public record ImportOutcome(UUID commitId, int elements, int relationships, List<Violation> discarded) {}

    /**
     * Applies an imported file's commands the tolerant way: keep what the language permits,
     * discard what it forbids, name every discard (ADR-0015).
     *
     * <p>For the importer only — the strict {@link #apply} stays the one door for everything
     * else. No {@code baseCommitId} from the caller: an import builds on whatever is current,
     * and the row lock inside {@code commit} serialises it against concurrent writers exactly
     * as it does for ordinary commands.
     */
    @Transactional
    public ImportOutcome importTolerantly(UUID modelId, List<ModelCommand> commands, String author, String message) {
        ModelGraph before = graphs.loadCurrent(modelId);
        ProfileCatalog profiles = graphs.loadProfiles(modelId, metamodel);

        ModelChange.ImportResult result = ModelChange.applyForImport(before, commands, metamodel, profiles);

        ModelGraph after = result.graph();
        UUID commitId = graphs.commit(modelId, before, after, author, message);
        return new ImportOutcome(
                commitId,
                after.elements().size() - before.elements().size(),
                after.relationships().size() - before.relationships().size(),
                result.discarded());
    }

    @Transactional(readOnly = true)
    public ModelGraph current(UUID modelId) {
        return graphs.loadCurrent(modelId);
    }

    /**
     * Defines a new profile, validated exactly as a loaded one is.
     *
     * <p>The rules — the base concept must exist and be specialisable, the name must not collide
     * with a concept or with another profile — live in {@code ProfileCatalog.Builder.build}, and
     * are applied here by rebuilding the catalogue with the newcomer in it. Restating them would
     * mean two places to be wrong in, and the one that matters is the one the reader trusts.
     */
    @Transactional
    public void defineProfile(UUID modelId, Profile profile) {
        ProfileCatalog.Builder builder = ProfileCatalog.builder();
        graphs.loadProfiles(modelId, metamodel).all().forEach(builder::profile);
        builder.profile(profile);
        builder.build(metamodel);
        graphs.insertProfile(modelId, profile);
    }

    /** @return false when there was no such profile; the caller turns that into a 404. */
    @Transactional
    public boolean removeProfile(UUID modelId, String name) {
        return graphs.deleteProfile(modelId, name) > 0;
    }

    @Transactional(readOnly = true)
    public ProfileCatalog profiles(UUID modelId) {
        return graphs.loadProfiles(modelId, metamodel);
    }

    @Transactional(readOnly = true)
    public List<ModelGraphRepository.CommitRow> history(UUID modelId, int limit) {
        return graphs.history(modelId, limit);
    }
}
