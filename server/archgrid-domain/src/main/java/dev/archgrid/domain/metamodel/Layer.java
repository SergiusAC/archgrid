package dev.archgrid.domain.metamodel;

/**
 * The ArchiMate layer an element type belongs to.
 *
 * <p>Derived mechanically from the upstream metamodel's type hierarchy rather than assigned
 * by hand — {@code ApplicationComponent} is an {@code ApplicationElement} there, and that is
 * where {@link #APPLICATION} comes from.
 *
 * <p>{@link #PHYSICAL} is deliberately its own value even though physical elements are also
 * technology elements upstream: architects filter physical views separately, and collapsing
 * the two would make that impossible without re-deriving it.
 */
public enum Layer {
    STRATEGY("strategy"),
    BUSINESS("business"),
    APPLICATION("application"),
    TECHNOLOGY("technology"),
    PHYSICAL("physical"),
    MOTIVATION("motivation"),
    IMPLEMENTATION_MIGRATION("implementation-migration"),
    /** Grouping, Location and Junction: usable across every layer, belonging to none. */
    OTHER("other");

    private final String code;

    Layer(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Layer fromCode(String code) {
        for (Layer layer : values()) {
            if (layer.code.equals(code)) {
                return layer;
            }
        }
        throw new IllegalArgumentException("unknown layer code: " + code);
    }
}
