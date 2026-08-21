package dev.archgrid.imports;

import dev.archgrid.model.ImportGateway;
import java.security.Principal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepts an ArchiMate Model Exchange File and imports it into the workspace's graph.
 *
 * <p>One endpoint, the file as the body. The response is the {@link ArchimateImportService.Report}
 * — what was kept, what was discarded by name (ADR-0015), and what the exchange format carries
 * that we have no place for. An import that cannot say what it dropped looks successful right up
 * to the first comparison with the original.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceKey}")
class ImportController {

    private final ImportGateway gateway;
    private final ArchimateImportService importer;

    ImportController(ImportGateway gateway, ArchimateImportService importer) {
        this.gateway = gateway;
        this.importer = importer;
    }

    @PostMapping(
            path = "/import/archimate",
            consumes = {"application/xml", "text/xml", "application/octet-stream"})
    @PreAuthorize("hasAnyRole('CONTRIBUTOR', 'OWNER', 'ADMIN')")
    ArchimateImportService.Report importArchimate(
            @PathVariable String workspaceKey, @RequestBody byte[] content, Principal principal) {
        UUID modelId = gateway.graphId(workspaceKey);
        return importer.importFile(modelId, content, principal.getName());
    }
}
