package dev.archgrid.model;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Layout, kept behind a service like everything else.
 *
 * <p>Thin to the point of looking pointless, and it is not: the architecture rules forbid a
 * controller from touching a repository, because a controller that can reach persistence can
 * bypass the command pipeline. Layout happens to be the one thing that legitimately skips
 * commands, but exempting it would leave the rule enforced by good intentions.
 */
@Service
class ViewLayoutService {

    private final ViewLayoutRepository repository;

    ViewLayoutService(ViewLayoutRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    List<ViewLayoutRepository.LayoutNode> load(UUID graphId, String viewKey) {
        return repository.load(graphId, viewKey);
    }

    @Transactional
    void save(UUID graphId, String viewKey, List<ViewLayoutRepository.LayoutNode> nodes) {
        repository.save(graphId, viewKey, nodes);
    }

    @Transactional(readOnly = true)
    String router(UUID graphId, String viewKey) {
        return repository.router(graphId, viewKey);
    }

    @Transactional
    void saveRouter(UUID graphId, String viewKey, String router) {
        repository.saveRouter(graphId, viewKey, router);
    }

    @Transactional(readOnly = true)
    List<String> hidden(UUID graphId, String viewKey) {
        return repository.hidden(graphId, viewKey);
    }

    @Transactional
    void saveHidden(UUID graphId, String viewKey, List<String> relationshipKeys) {
        repository.saveHidden(graphId, viewKey, relationshipKeys);
    }
}
