package dev.archgrid.model;

import dev.archgrid.admin.workspace.WorkspaceService;
import dev.archgrid.domain.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The model slice's door for the importer, and nothing wider.
 *
 * <p>The services behind it are package-private on purpose — the command pipeline is the one way
 * to change a model, and layout has exactly one endpoint. An importer legitimately needs both
 * (ADR-0015 for the commit, ADR-0003 for the arrangement), and opening the classes themselves
 * would open them to everything. This facade names the operations an import consists of and no
 * others.
 */
@Service
public class ImportGateway {

    private final WorkspaceService workspaces;
    private final GraphService graphs;
    private final ViewLayoutService layout;
    private final FolderService folders;

    ImportGateway(WorkspaceService workspaces, GraphService graphs, ViewLayoutService layout, FolderService folders) {
        this.workspaces = workspaces;
        this.graphs = graphs;
        this.layout = layout;
        this.folders = folders;
    }

    public UUID graphId(String workspaceKey) {
        Workspace workspace = workspaces
                .findByKey(dev.archgrid.domain.shared.Slug.of(workspaceKey))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No workspace '" + workspaceKey + "'."));
        return graphs.of(workspace.id(), workspace.name()).id();
    }

    /** One drawing of an element on a view (ADR-0014), in the importer's own flat terms. */
    public record PlacementRow(UUID id, UUID element, double x, double y, double width, double height, UUID parent) {}

    /** One line on a view; {@code vertices} are absolute points, as the exchange format has them. */
    public record LineRow(UUID id, UUID relationship, UUID sourceNode, UUID targetNode, List<double[]> vertices) {}

    public void savePlacements(UUID modelId, UUID viewId, List<PlacementRow> rows) {
        layout.save(
                modelId,
                viewId,
                rows.stream()
                        .map(row -> new ViewLayoutRepository.LayoutNode(
                                row.id(), row.element(), row.x(), row.y(), row.width(), row.height(), row.parent(),
                                null))
                        .toList());
    }

    public void saveLines(UUID modelId, UUID viewId, List<LineRow> rows) {
        layout.saveLinks(
                modelId,
                viewId,
                rows.stream()
                        .map(row -> new ViewLayoutRepository.LayoutLink(
                                row.id(),
                                row.relationship().toString(),
                                row.sourceNode(),
                                row.targetNode(),
                                row.vertices().stream()
                                        .map(point -> new ViewLayoutRepository.Point(point[0], point[1]))
                                        .toList()))
                        .toList());
    }

    public void saveHidden(UUID modelId, UUID viewId, List<UUID> relationshipIds) {
        layout.saveHidden(modelId, viewId, relationshipIds);
    }

    /**
     * Finds the folder of that name at that level, or creates it. Filing is organisation, not
     * content: a second import filing into 'Business' belongs in the existing 'Business', and the
     * database's uniqueness of sibling names would refuse a blind create anyway.
     *
     * @return the folder's id, for parenting and for filing
     */
    public UUID createFolder(UUID modelId, String kind, String name, UUID parent) {
        String parentId = parent == null ? null : parent.toString();
        return folders.list(modelId, kind).stream()
                .filter(folder -> folder.name().equalsIgnoreCase(name.trim())
                        && java.util.Objects.equals(folder.parent(), parentId))
                .findFirst()
                .map(folder -> UUID.fromString(folder.id()))
                .orElseGet(() -> UUID.fromString(
                        folders.create(modelId, kind, name, parentId).id()));
    }

    public void assignToFolder(UUID modelId, String kind, UUID id, UUID folderId) {
        folders.assign(modelId, kind, id, folderId);
    }
}
