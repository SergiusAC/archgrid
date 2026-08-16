package dev.archgrid.domain.metamodel.c4;

/**
 * How an ArchiMate element renders in a C4 view.
 *
 * <p>Notice what is absent: there is no {@code SOFTWARE_SYSTEM}, {@code CONTAINER} or
 * {@code COMPONENT}. Those are not shapes, they are levels, and an element's level depends on
 * its depth relative to the focus of the view rather than on its type — see ADR-0007. A
 * {@link #BOX} rendered on a context diagram is a software system; the same element on the
 * container diagram of its parent is a container.
 */
public enum C4Shape {
    /** A human: C4's Person. */
    PERSON("person"),
    /** The rectangle whose C4 level comes from view depth. */
    BOX("box"),
    /** A system or container boundary. */
    BOUNDARY("boundary"),
    /**
     * Not representable in C4 at all.
     *
     * <p>The correct answer for most of ArchiMate: behaviour, interfaces, motivation,
     * strategy and implementation elements have no C4 equivalent. This is an explicit outcome
     * so that a view can report what it is not showing instead of inventing a shape.
     */
    OMITTED("omitted");

    private final String code;

    C4Shape(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static C4Shape fromCode(String code) {
        for (C4Shape shape : values()) {
            if (shape.code.equals(code)) {
                return shape;
            }
        }
        throw new IllegalArgumentException("unknown C4 shape code: " + code);
    }
}
