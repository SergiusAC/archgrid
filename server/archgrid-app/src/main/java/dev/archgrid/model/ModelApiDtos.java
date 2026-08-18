package dev.archgrid.model;

import dev.archgrid.domain.model.Element;
import dev.archgrid.domain.model.ModelCommand;
import dev.archgrid.domain.model.Relationship;
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
            String parent,
            String source,
            String target,
            String property,
            String value,
            String viewpoint,
            String element) {

        ModelCommand toCommand() {
            Slug subject = slug(key, "key");
            return switch (op) {
                case "create-element" -> new ModelCommand.CreateElement(
                        subject, require(type, "type"), require(name, "name"), documentation, optionalSlug(parent));
                case "rename-element" -> new ModelCommand.RenameElement(subject, require(name, "name"));
                case "set-element-documentation" -> new ModelCommand.SetElementDocumentation(subject, documentation);
                case "set-element-property" -> new ModelCommand.SetElementProperty(
                        subject, require(property, "property"), value);
                case "move-element" -> new ModelCommand.MoveElement(subject, optionalSlug(parent));
                case "delete-element" -> new ModelCommand.DeleteElement(subject);
                case "add-relationship" -> new ModelCommand.AddRelationship(
                        subject,
                        require(type, "type"),
                        slug(require(source, "source"), "source"),
                        slug(require(target, "target"), "target"),
                        name);
                case "delete-relationship" -> new ModelCommand.DeleteRelationship(subject);
                case "create-view" -> new ModelCommand.CreateView(
                        subject, require(name, "name"), require(viewpoint, "viewpoint"));
                case "rename-view" -> new ModelCommand.RenameView(subject, require(name, "name"));
                case "set-viewpoint" -> new ModelCommand.SetViewpoint(subject, require(viewpoint, "viewpoint"));
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
            String parent) {

        static ElementResponse from(Element element) {
            return new ElementResponse(
                    element.key().value(),
                    element.displayType(),
                    element.conceptType(),
                    element.profileName(),
                    element.name(),
                    element.documentation(),
                    element.properties(),
                    element.parentKey().map(Slug::value).orElse(null));
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
     */
    record ViewResponse(
            String key,
            String name,
            String viewpoint,
            String folder,
            List<ElementResponse> elements,
            List<RelationshipResponse> relationships,
            List<DerivedRelationshipResponse> derived,
            List<LayoutNodeResponse> layout,
            /** How this view draws its connections: 'straight' or 'orthogonal'. Presentation only. */
            String router) {}

    /**
     * A relationship the model implies but nobody drew.
     *
     * <p>Marked apart from explicit ones on purpose: an architect must be able to tell what the
     * model says from what follows from it, and `via` answers "why is this line here".
     */
    record DerivedRelationshipResponse(String key, String type, String source, String target, List<String> via) {}

    record LayoutNodeResponse(String element, double x, double y, Double width, Double height) {}

    record LayoutNodeRequest(@NotBlank String element, double x, double y, Double width, Double height) {}

    /** Coordinates never travel as commands: moving a box is not a model change (ADR-0003). */
    /** `router` is optional: a plain drag saves coordinates and must not disturb the routing. */
    record LayoutRequest(@Valid List<LayoutNodeRequest> nodes, String router) {}

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

    record CommitResponseItem(UUID id, long seq, String author, String message, Instant createdAt) {

        static CommitResponseItem from(ModelGraphRepository.CommitRow row) {
            return new CommitResponseItem(row.id(), row.seq(), row.author(), row.message(), row.createdAt());
        }
    }
}
