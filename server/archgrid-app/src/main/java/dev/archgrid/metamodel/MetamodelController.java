package dev.archgrid.metamodel;

import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipType;
import dev.archgrid.domain.metamodel.Viewpoint;
import dev.archgrid.domain.metamodel.c4.C4Mapping;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves the metamodel to the editor.
 *
 * <p>The whole thing at once, including the 62 × 62 permitted matrix, because the editor has to
 * answer "may this relationship exist" while the user is still dragging. A round trip per
 * mouse-move would make the one feature that distinguishes this from a drawing tool feel
 * broken, so the client gets the table and decides locally. The server still re-checks on
 * commit — the client copy is for feedback, never for authority (ADR-0004).
 *
 * <p>The matrix travels in the same letter encoding the metamodel file uses, which keeps the
 * payload around a tenth of what a fully expanded form would cost.
 */
@RestController
@RequestMapping("/api/metamodel")
class MetamodelController {

    private final Metamodel metamodel;
    private final C4Mapping c4Mapping;

    /**
     * The payload and its tag, built once.
     *
     * <p>The metamodel cannot change while the process runs, so there is nothing to recompute and
     * the tag is exact rather than a guess about what might have changed.
     */
    private final MetamodelDto payload;

    private final String etag;

    MetamodelController(Metamodel metamodel, C4Mapping c4Mapping, ObjectMapper json) {
        this.metamodel = metamodel;
        this.c4Mapping = c4Mapping;
        this.payload = new MetamodelDto(
                metamodel.language(),
                metamodel.version(),
                elementTypes(),
                relationshipTypes(),
                viewpoints(),
                c4Levels(),
                permitted());
        this.etag = tag(json.writeValueAsString(payload));
    }

    private static String tag(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the platform", ex);
        }
    }

    record ElementTypeDto(String concept, String name, String layer, String aspect, String c4Shape) {}

    record RelationshipTypeDto(String concept, String name, String key, String category, int strength) {}

    record ViewpointDto(String code, String name, List<String> concepts) {}

    /** A C4 level: a name and a depth relative to a view's focus, nothing stored on an element. */
    record C4LevelDto(String code, String name, int depth) {}

    record MetamodelDto(
            String language,
            String version,
            List<ElementTypeDto> elementTypes,
            List<RelationshipTypeDto> relationshipTypes,
            List<ViewpointDto> viewpoints,
            List<C4LevelDto> c4Levels,
            Map<String, Map<String, String>> permitted) {}

    /**
     * Immutable for the lifetime of a process, and revalidated rather than cached blind.
     *
     * <p>It used to carry {@code max-age=12h}, on the reasoning that the metamodel does not change
     * and the payload is large. Both are true and the conclusion was still wrong: when the server
     * started sending a new field, every browser that had asked in the last twelve hours went on
     * using the old shape, and the feature that field enabled was invisible with nothing in the
     * console to say why. A conditional request costs one round trip and answers 304 with no body;
     * that is the right price for never serving a client a payload older than the server's.
     */
    @GetMapping
    ResponseEntity<MetamodelDto> get(WebRequest request) {
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(payload);
    }

    private List<ElementTypeDto> elementTypes() {
        return metamodel.elementTypes().stream()
                .map(type -> new ElementTypeDto(
                        type.concept(),
                        type.name(),
                        type.layer().code(),
                        type.aspect().code(),
                        c4Mapping.shapeOf(type).code()))
                .toList();
    }

    private List<RelationshipTypeDto> relationshipTypes() {
        return metamodel.relationshipTypes().stream()
                .map(type -> new RelationshipTypeDto(
                        type.concept(),
                        type.name(),
                        String.valueOf(type.key()),
                        type.category().code(),
                        type.strength()))
                .toList();
    }

    private List<ViewpointDto> viewpoints() {
        return metamodel.viewpoints().stream()
                .map(viewpoint -> new ViewpointDto(
                        viewpoint.code(), viewpoint.name(), List.copyOf(viewpoint.concepts())))
                .toList();
    }

    /**
     * The C4 levels, so a client can offer them without knowing what they are.
     *
     * <p>Data rather than three constants in the UI: the levels are hand-authored alongside the
     * shape table, and a fourth one would otherwise mean editing both (ADR-0007).
     */
    private List<C4LevelDto> c4Levels() {
        return c4Mapping.levels().stream()
                .map(level -> new C4LevelDto(level.code(), level.name(), level.depth()))
                .toList();
    }

    /** source concept -&gt; target concept -&gt; relationship keys, matching the metamodel file's encoding. */
    private Map<String, Map<String, String>> permitted() {
        Map<String, Map<String, String>> matrix = new TreeMap<>();
        for (ElementType source : metamodel.elementTypes()) {
            Map<String, String> row = new LinkedHashMap<>();
            for (ElementType target : metamodel.elementTypes()) {
                var types = metamodel.permittedBetween(source.concept(), target.concept());
                if (types.isEmpty()) {
                    continue;
                }
                StringBuilder keys = new StringBuilder(types.size());
                types.stream().map(RelationshipType::key).sorted().forEach(keys::append);
                row.put(target.concept(), keys.toString());
            }
            matrix.put(source.concept(), row);
        }
        return matrix;
    }

    /** Viewpoint palettes as a convenience for the editor: which concepts each viewpoint offers. */
    @GetMapping("/viewpoints")
    List<ViewpointDto> viewpointList() {
        return viewpoints();
    }

    record ViewpointPaletteDto(String code, String name, List<ElementTypeDto> concepts) {}

    /**
     * A viewpoint's palette, already expanded and ordered by layer.
     *
     * <p>An empty concept list upstream means "everything is allowed" — the Layered viewpoint —
     * so it is expanded here rather than leaving each client to rediscover that rule.
     */
    @GetMapping("/viewpoints/{code}/palette")
    ResponseEntity<ViewpointPaletteDto> palette(@org.springframework.web.bind.annotation.PathVariable String code) {
        Viewpoint viewpoint = metamodel.viewpoint(code)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "No viewpoint '" + code + "'."));
        List<ElementTypeDto> concepts = elementTypes().stream()
                .filter(type -> viewpoint.allows(type.concept()))
                .filter(type -> metamodel
                        .elementType(type.concept())
                        .map(resolved -> !resolved.isPseudoElement())
                        .orElse(false))
                .toList();
        return ResponseEntity.ok(new ViewpointPaletteDto(viewpoint.code(), viewpoint.name(), concepts));
    }
}
