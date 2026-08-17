package dev.archgrid.admin.workspace;

import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.Workspace;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public because other feature slices need to resolve a workspace, and the architecture rules
 * require them to come through a slice's service rather than reach into its tables.
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository repository;

    WorkspaceService(WorkspaceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    List<Workspace> listAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Workspace> findByKey(Slug key) {
        return repository.findByKey(key);
    }

    /**
     * Creates a repository. Its graph is provisioned on first read, so nothing else is set up
     * here — a workspace that exists but has never been opened is a perfectly valid state.
     */
    @Transactional
    Workspace create(Slug key, String name) {
        repository.create(key, name);
        return repository.findByKey(key).orElseThrow();
    }
}
