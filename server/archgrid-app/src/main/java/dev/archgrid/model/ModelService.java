package dev.archgrid.model;

import dev.archgrid.domain.shared.Slug;
import dev.archgrid.domain.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Model registry operations. Changing a model's <em>content</em> goes through {@link ModelCommandService}. */
@Service
class ModelService {

    private final ModelRepository repository;

    ModelService(ModelRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    List<ModelRepository.ModelRow> list(WorkspaceId workspaceId) {
        return repository.findAll(workspaceId);
    }

    @Transactional(readOnly = true)
    Optional<ModelRepository.ModelRow> find(WorkspaceId workspaceId, Slug key) {
        return repository.find(workspaceId, key);
    }

    @Transactional
    ModelRepository.ModelRow create(WorkspaceId workspaceId, Slug key, String name, String description) {
        repository.create(workspaceId, key, name, description);
        return repository.find(workspaceId, key).orElseThrow();
    }
}
