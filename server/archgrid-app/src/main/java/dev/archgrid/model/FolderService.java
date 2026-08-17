package dev.archgrid.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Folder operations. Not versioned, so plain reads and writes rather than commands (ADR-0009). */
@Service
class FolderService {

    private static final List<String> KINDS = List.of("elements", "views");

    private final FolderRepository repository;

    FolderService(FolderRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    List<ModelApiDtos.FolderResponse> list(UUID graphId, String kind) {
        return repository.findAll(graphId, requireKind(kind)).stream()
                .map(FolderService::toResponse)
                .toList();
    }

    /** view key -&gt; folder id, so the view list can be rendered as a tree in one pass. */
    @Transactional(readOnly = true)
    Map<String, String> viewFolders(UUID graphId) {
        Map<String, String> byKey = new LinkedHashMap<>();
        repository.assignments(graphId, "view").forEach(row -> byKey.put(row[0], row[1]));
        return byKey;
    }

    @Transactional(readOnly = true)
    Map<String, String> elementFolders(UUID graphId) {
        Map<String, String> byKey = new LinkedHashMap<>();
        repository.assignments(graphId, "element").forEach(row -> byKey.put(row[0], row[1]));
        return byKey;
    }

    @Transactional
    ModelApiDtos.FolderResponse create(UUID graphId, String kind, String name, String parent) {
        UUID parentId = parseFolder(parent);
        if (parentId != null) {
            requireFolder(graphId, parentId);
        }
        UUID id = repository.create(graphId, parentId, requireKind(kind), name.trim());
        return repository
                .find(graphId, id)
                .map(FolderService::toResponse)
                .orElseThrow();
    }

    @Transactional
    ModelApiDtos.FolderResponse update(UUID graphId, UUID folderId, String name, String parent) {
        requireFolder(graphId, folderId);
        UUID parentId = parseFolder(parent);
        if (parentId != null) {
            requireFolder(graphId, parentId);
            // A cycle makes the tree infinite to render, and the database cannot express the rule.
            if (repository.wouldCycle(graphId, folderId, parentId)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "That would put the folder inside itself, directly or through its own children.");
            }
        }
        repository.rename(graphId, folderId, name.trim());
        repository.move(graphId, folderId, parentId);
        return repository
                .find(graphId, folderId)
                .map(FolderService::toResponse)
                .orElseThrow();
    }

    @Transactional
    void delete(UUID graphId, UUID folderId) {
        requireFolder(graphId, folderId);
        repository.delete(graphId, folderId);
    }

    @Transactional
    void assign(UUID graphId, String kind, String key, UUID folderId) {
        if (folderId != null) {
            requireFolder(graphId, folderId);
        }
        switch (requireKind(kind)) {
            case "elements" -> repository.assignElement(graphId, key, folderId);
            case "views" -> repository.assignView(graphId, key, folderId);
            default -> throw new IllegalStateException("unreachable");
        }
    }

    private void requireFolder(UUID graphId, UUID folderId) {
        repository
                .find(graphId, folderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No folder " + folderId + "."));
    }

    private static String requireKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("Folder kind must be one of " + KINDS + ", was '" + kind + "'.");
        }
        return kind;
    }

    private static UUID parseFolder(String parent) {
        if (parent == null || parent.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(parent);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("'" + parent + "' is not a folder id.");
        }
    }

    private static ModelApiDtos.FolderResponse toResponse(FolderRepository.FolderRow row) {
        return new ModelApiDtos.FolderResponse(
                row.id().toString(),
                row.name(),
                row.kind(),
                row.parentId() == null ? null : row.parentId().toString());
    }
}
