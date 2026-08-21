package dev.archgrid.model;

import dev.archgrid.domain.model.Element;
import dev.archgrid.domain.model.ModelCommand;
import dev.archgrid.domain.model.Relationship;
import dev.archgrid.domain.model.ViewFilter;
import dev.archgrid.domain.shared.Slug;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire contract for the model API.
 *
 * <p>Separate from the domain records on purpose: these are published shapes the web client and
 * the future CLI depend on, while the domain is free to change as the model grows.
 *
 * <p>Commands arrive as a flat object discriminated by {@code op} rather than through Jackson
 * polymorphism. The mapping is written out by hand below, which costs a switch statement and
 * buys an error message that names the operation and the field when a client gets it wrong.
 */
final class ModelApiDtos {

    private ModelApiDtos() {}

    /**
     * The workspace's graph.
     *
     * <p>Carries no model identifier: a workspace holds exactly one graph, and exposing an id
     * for it would invite clients to start addressing several (ADR-0008).
     */
    record GraphResponse(String workspace, String name, UUID headCommitId, int elements, int relationships) {}

    record CommandRequest(
            UUID baseCommitId, @Size(max = 500) String message, @NotEmpty @Valid List<CommandDto> commands) {}

    /**
     * @param op the operation, e.g. {@code create-element}
     * @param type an ArchiMate concept or, for elements, a profile defined in this model
     */
    record CommandDto(
            @NotBlank String op,
            @NotBlank String key,
            String type,
            String name,
            String documentation,
            String source,
            String target,
            String property,
            String value,
            String viewpoint,
            String element,
            /** Conditions on what a view draws; only `set-view-filters` uses them. */
            List<FilterDto> filters) {

        ModelCommand toCommand() {
            Slug subject = slug(key, "key");
            return switch (op) {
                case "create-element" -> new ModelCommand.CreateElement(
                        subject, require(type, "type"), require(name, "name"), documentation);
                case "rename-element" -> new ModelCommand.RenameElement(subject, require(name, "name"));
                case "set-element-documentation" -> new ModelCommand.SetElementDocumentation(subject, documentation);
                case "set-element-property" -> new ModelCommand.SetElementProperty(
                        subject, require(property, "property"), value);
                case "delete-element" -> new ModelCommand.DeleteElement(subject);
                case "add-relationship" -> new ModelCommand.AddRelationship(
                        subject,
                        require(type, "type"),
                        slug(require(source, "source"), "source"),
                        slug(require(target, "target"), "target"),
                        name);
                case "set-relationship-property" -> new ModelCommand.SetRelationshipProperty(
                        subject, require(property, "property"), value);
                case "delete-relationship" -> new ModelCommand.DeleteRelationship(subject);
                case "create-view" -> new ModelCommand.CreateView(
                        subject, require(name, "name"), require(viewpoint, "viewpoint"));
                case "rename-view" -> new ModelCommand.RenameView(subject, require(name, "name"));
                case "set-viewpoint" -> new ModelCommand.SetViewpoint(subject, require(viewpoint, "viewpoint"));
                case "set-view-filters" -> new ModelCommand.SetViewFilters(
                        subject,
                        (filters == null ? List.<FilterDto>of() : filters)
                                .stream().map(FilterDto::toFilter).toList());
                case "delete-view" -> new ModelCommand.DeleteView(subject);
                case "add-to-view" -> new ModelCommand.AddToView(subject, slug(require(element, "element"), "element"));
                case "remove-from-view" -> new ModelCommand.RemoveFromView(
                        subject, slug(require(element, "element"), "element"));
                default -> throw new IllegalArgumentException("Unknown operation '" + op
                        + "'. Supported: create-element, rename-element, set-element-documentation, "
                        + "set-element-property, move-element, delete-element, add-relationship, "
                        + "delete-relationship, create-view, rename-view, set-viewpoint, delete-view, "
                        + "add-to-view, remove-from-view.");
            };
        }

        private String require(String field, String fieldName) {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("Operation '" + op + "' requires '" + fieldName + "'.");
            }
            return field;
        }

        private static Slug slug(String raw, String fieldName) {
            try {
                return Slug.of(raw);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Field '" + fieldName + "' is not a valid key: " + ex.getMessage());
            }
        }

        private static Slug optionalSlug(String raw) {
            return raw == null || raw.isBlank() ? null : Slug.of(raw);
        }
    }

    record CommitResponse(UUID commitId, int elements, int relationships) {}

    record ElementResponse(
            String key,
            String type,
            String conceptType,
            String profile,
            String name,
            String documentation,
            Map<String, String> properties,
            /** Which folder it is filed under. Organisation only, unversioned (ADR-0009). */
            String folder) {

        static ElementResponse from(Element element) {
            return from(element, null);
        }

        static ElementResponse from(Element element, String folder) {
            return new ElementResponse(
                    element.key().value(),
                    element.displayType(),
                    element.conceptType(),
                    element.profileName(),
                    element.name(),
                    element.documentation(),
                    element.properties(),
                    folder);
        }
    }

    record RelationshipResponse(
            String key, String type, String source, String target, String name, Map<String, String> properties) {

        static RelationshipResponse from(Relationship relationship) {
            return new RelationshipResponse(
                    relationship.key().value(),
                    relationship.conceptType(),
                    relationship.source().value(),
                    relationship.target().value(),
                    relationship.name(),
                    relationship.properties());
        }
    }

    /** Where an element is used: relationships and the views showing it. */
    record UsageResponse(List<RelationshipResponse> relationships, List<ViewRefResponse> views) {}

    record ViewRefResponse(String key, String name) {}

    /** A view in the list: enough to draw the tree, without its contents. */
    record ViewSummaryResponse(String key, String name, String viewpoint, String folder, int elements) {

        static ViewSummaryResponse from(dev.archgrid.domain.model.View view, String folder) {
            return new ViewSummaryResponse(
                    view.key().value(), view.name(), view.viewpoint(), folder, view.elements().size());
        }
    }

    /**
     * A view with everything needed to draw it.
     *
     * <p>Relationships are computed rather than stored: those with both endpoints on the view.
     * Only stated ones — a view does not draw what the model merely implies (ADR-0013).
     */
    record ViewResponse(
            String key,
            String name,
            String viewpoint,
            String folder,
            List<ElementResponse> elements,
            List<RelationshipResponse> relationships,
            List<LayoutNodeResponse> layout,
            /** How the lines are routed here. Empty for every connection nobody has bent. */
            List<LayoutLinkResponse> layoutLinks,
            /** Conditions on what this view draws. Versioned with its name and viewpoint (ADR-0003). */
            List<FilterDto> filters,
            /** Members the filters leave out — named, so a narrowed diagram cannot pass for a full one. */
            List<OmittedResponse> filtered,
            /** Relationships this view does not draw. Presentation only; the model still holds them. */
            List<String> hidden,
            /** How this view draws its connections: 'straight' or 'orthogonal'. Presentation only. */
            String router) {}

    /**
     * A relationship the model implies but nobody drew.
     *
     * <p>Marked apart from explicit ones on purpose: an architect must be able to tell what the
     * model says from what follows from it, and `via` answers "why is this line here".
     */
    /**
     * The graph seen as C4 around one element (ADR-0007).
     *
     * <p>`omitted` is not an error list: C4 represents ten of ArchiMate's sixty-two concepts, and
     * a projection that dropped the rest in silence would look complete while hiding half the
     * model.
     */
    /**
     * One condition on what a view draws.
     *
     * @param kind {@code layer} or {@code property}
     * @param name the property key; unused for a layer
     * @param value the layer code, or the property value. Null on a property filter asks for
     *     everything that has the property at all.
     */
    record FilterDto(String kind, String name, String value) {

        ViewFilter toFilter() {
            if (kind == null || kind.isBlank()) {
                // The same shape as every other bad command: an explanation, not a 500.
                throw new IllegalArgumentException("A view filter needs a kind: 'layer' or 'property'.");
            }
            return new ViewFilter(ViewFilter.Kind.of(kind), name, value);
        }

        static FilterDto from(ViewFilter filter) {
            return new FilterDto(filter.kind().code(), filter.name(), filter.value());
        }
    }

    record C4ProjectionResponse(
            String level,
            String levelName,
            String focus,
            List<ElementResponse> elements,
            List<RelationshipResponse> relationships,
            List<DerivedRelationshipResponse> derived,
            List<OmittedResponse> omitted) {}

    record OmittedResponse(String key, String name, String type) {}

    record DerivedRelationshipResponse(String key, String type, String source, String target, List<String> via) {}

    /**
     * One placement: a single drawing of an element on this view (ADR-0014).
     *
     * <p>`id` names the placement, not the element — the same element may be drawn several times,
     * and `parent` is the placement this one sits inside rather than the element (ADR-0011).
     */
    record LayoutNodeResponse(
            UUID id,
            String element,
            double x,
            double y,
            Double width,
            Double height,
            UUID parent,
            Integer z) {}

    /**
     * Where a line bends on this view. Presentation, like the coordinates it travels with.
     *
     * <p>`key` is the line as the diagram knows it: a relationship key, or the computed key of a
     * derived line. Not named `relationship`, because half of them are not one.
     */
    record LayoutLinkResponse(
            UUID id, String key, UUID sourceNode, UUID targetNode, List<PointDto> vertices) {}

    /** `sourceNode` and `targetNode` are null while the line follows the only placement there is. */
    record LayoutLinkRequest(
            UUID id, @NotBlank String key, UUID sourceNode, UUID targetNode, List<PointDto> vertices) {}

    record PointDto(double x, double y) {}

    record LayoutNodeRequest(
            UUID id,
            @NotBlank String element,
            double x,
            double y,
            Double width,
            Double height,
            UUID parent,
            Integer z) {}

    /** Coordinates never travel as commands: moving a box is not a model change (ADR-0003). */
    /**
     * `router` and `hidden` are optional: a plain drag saves coordinates and must not carry an
     * opinion about routing, or about which lines this view leaves out.
     */
    record LayoutRequest(
            @Valid List<LayoutNodeRequest> nodes,
            @Valid List<LayoutLinkRequest> links,
            String router,
            List<String> hidden) {}

    record FolderRequest(@NotBlank @Size(max = 120) String name, String parent) {}

    record FolderResponse(String id, String name, String kind, String parent) {}

    record ProfileResponse(String name, String conceptType, List<AttributeResponse> attributes) {

        static ProfileResponse from(dev.archgrid.domain.profile.Profile profile) {
            return new ProfileResponse(
                    profile.name(),
                    profile.conceptType(),
                    profile.attributes().stream()
                            .map(attribute -> new AttributeResponse(
                                    attribute.key().value(), attribute.name(), attribute.type().code()))
                            .toList());
        }
    }

    record AttributeResponse(String key, String name, String type) {}

    /**
     * A profile as the client defines one.
     *
     * <p>Attribute types are exactly the exchange format's six, so nothing an architect records
     * here is lost on export (ADR-0002).
     */
    record ProfileRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank String concept,
            List<AttributeRequest> attributes) {

        dev.archgrid.domain.profile.Profile toProfile() {
            return new dev.archgrid.domain.profile.Profile(
                    name,
                    concept,
                    (attributes == null ? List.<AttributeRequest>of() : attributes)
                            .stream().map(AttributeRequest::toDefinition).toList());
        }
    }

    record AttributeRequest(@NotBlank String key, @NotBlank String name, @NotBlank String type) {

        dev.archgrid.domain.profile.AttributeDefinition toDefinition() {
            return dev.archgrid.domain.profile.AttributeDefinition.of(
                    key, name, dev.archgrid.domain.profile.AttributeType.fromCode(type));
        }
    }

    record CommitResponseItem(UUID id, long seq, String author, String message, Instant createdAt) {

        static CommitResponseItem from(ModelGraphRepository.CommitRow row) {
            return new CommitResponseItem(row.id(), row.seq(), row.author(), row.message(), row.createdAt());
        }
    }
}
