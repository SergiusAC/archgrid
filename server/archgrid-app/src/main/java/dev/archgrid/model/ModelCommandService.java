package dev.archgrid.model;

import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.model.ModelChange;
import dev.archgrid.domain.model.ModelCommand;
import dev.archgrid.domain.model.ModelGraph;
import dev.archgrid.domain.model.Violation;
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

    @Transactional(readOnly = true)
    public ModelGraph current(UUID modelId) {
        return graphs.loadCurrent(modelId);
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
