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

    record CreateModelRequest(
            @NotBlank @Size(max = 64) String key,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description) {}

    record ModelResponse(UUID id, String key, String name, String description, UUID headCommitId) {

        static ModelResponse from(ModelRepository.ModelRow row) {
            return new ModelResponse(row.id(), row.key().value(), row.name(), row.description(), row.headCommitId());
        }
    }

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
            String value) {

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
                default -> throw new IllegalArgumentException("Unknown operation '" + op
                        + "'. Supported: create-element, rename-element, set-element-documentation, "
                        + "set-element-property, move-element, delete-element, add-relationship, delete-relationship.");
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
