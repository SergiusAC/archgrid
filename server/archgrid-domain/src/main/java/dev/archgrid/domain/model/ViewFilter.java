package dev.archgrid.domain.model;

import java.util.Locale;
import java.util.Objects;

/**
 * One condition a view puts on what it draws.
 *
 * <p>A filter does not change what is on the view. Membership is a versioned decision — "this
 * diagram is about these elements" — and a filter is a second, narrower statement on top of it:
 * "and of those, show the technology layer". Which is why removing a filter brings the elements
 * back exactly where they were: their coordinates never went anywhere.
 *
 * <p>Two kinds, because two are what the repository can answer with certainty. A layer is part of
 * the metamodel and cannot be mistyped into meaninglessness; a property is what an architect
 * actually uses to mark things — {@code tag=legacy}, {@code owner=payments} — and a "tag" is a
 * property with a well-known name rather than a third mechanism.
 *
 * @param name the property key; unused and null for a layer filter
 * @param value the layer code, or the property value. Null on a property filter means "has this
 *     property at all", which is how "everything with an owner" is asked for.
 */
public record ViewFilter(Kind kind, String name, String value) {

    public enum Kind {
        LAYER,
        PROPERTY;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind of(String code) {
            Objects.requireNonNull(code, "code must not be null");
            for (Kind kind : values()) {
                if (kind.code().equals(code.toLowerCase(Locale.ROOT))) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown view filter kind: " + code);
        }
    }

    public ViewFilter {
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind == Kind.LAYER) {
            Objects.requireNonNull(value, "a layer filter needs a layer");
            name = null;
        } else {
            Objects.requireNonNull(name, "a property filter needs a property name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("a property filter needs a property name");
            }
        }
    }

    public static ViewFilter layer(String layer) {
        return new ViewFilter(Kind.LAYER, null, layer);
    }

    public static ViewFilter property(String name, String value) {
        return new ViewFilter(Kind.PROPERTY, name, value);
    }
}
