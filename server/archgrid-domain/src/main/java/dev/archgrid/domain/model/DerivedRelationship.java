package dev.archgrid.domain.model;

import dev.archgrid.domain.metamodel.RelationshipType;
import dev.archgrid.domain.shared.Slug;
import java.util.List;
import java.util.Objects;

/**
 * A relationship that is not in the model but follows from it.
 *
 * <p>Never stored. Storing derived relationships would make the model's own history unreadable
 * — every explicit change would drag a cloud of implied ones behind it — and would go stale the
 * moment anything on the path changed. They are computed when a view asks.
 *
 * @param via the hidden elements the chain passed through, in order. Kept so the diagram can
 *     answer "why is this line here", which is the first thing an architect asks of a
 *     relationship they did not draw.
 */
public record DerivedRelationship(Slug source, Slug target, RelationshipType type, List<Slug> via) {

    public DerivedRelationship {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(type, "type must not be null");
        via = List.copyOf(via);
    }

    /** A stable synthetic key, so the same derived line keeps its identity between renders. */
    public String key() {
        return "derived:" + source.value() + ":" + type.concept().toLowerCase(java.util.Locale.ROOT) + ":"
                + target.value();
    }

    public String explain(String sourceName, String targetName, List<String> viaNames) {
        return "%s from %s to %s, derived through %s."
                .formatted(type.name(), sourceName, targetName, String.join(" → ", viaNames));
    }
}
