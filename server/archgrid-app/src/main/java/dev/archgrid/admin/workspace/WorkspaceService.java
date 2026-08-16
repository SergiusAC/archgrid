package dev.archgrid.admin.workspace;

import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.Workspace;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkspaceService {

    private final WorkspaceRepository repository;

    WorkspaceService(WorkspaceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    List<Workspace> listAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    Optional<Workspace> findByKey(Slug key) {
        return repository.findByKey(key);
    }
}
