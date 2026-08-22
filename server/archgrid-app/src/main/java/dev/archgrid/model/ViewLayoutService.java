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
    List<ViewLayoutRepository.LayoutNode> load(UUID graphId, UUID viewId) {
        return repository.load(graphId, viewId);
    }

    @Transactional
    void save(UUID graphId, UUID viewId, List<ViewLayoutRepository.LayoutNode> nodes) {
        repository.save(graphId, viewId, nodes);
    }

    @Transactional(readOnly = true)
    String router(UUID graphId, UUID viewId) {
        return repository.router(graphId, viewId);
    }

    @Transactional
    void saveRouter(UUID graphId, UUID viewId, String router) {
        repository.saveRouter(graphId, viewId, router);
    }

    @Transactional(readOnly = true)
    List<ViewLayoutRepository.LayoutLink> links(UUID graphId, UUID viewId) {
        return repository.links(graphId, viewId);
    }

    @Transactional
    void saveLinks(UUID graphId, UUID viewId, List<ViewLayoutRepository.LayoutLink> links) {
        repository.saveLinks(graphId, viewId, links);
    }

    @Transactional(readOnly = true)
    List<UUID> hidden(UUID graphId, UUID viewId) {
        return repository.hidden(graphId, viewId);
    }

    @Transactional
    void saveHidden(UUID graphId, UUID viewId, List<UUID> relationshipIds) {
        repository.saveHidden(graphId, viewId, relationshipIds);
    }
}
