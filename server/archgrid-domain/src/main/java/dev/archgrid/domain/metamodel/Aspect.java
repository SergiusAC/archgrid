package dev.archgrid.domain.metamodel;

/**
 * Which of the three ArchiMate aspects an element type expresses.
 *
 * <p>{@link #NONE} is not a gap in the data. Motivation elements, junctions and groupings
 * genuinely sit outside the active/behaviour/passive classification, and forcing them into
 * one of the three would be an invention.
 */
public enum Aspect {
    ACTIVE_STRUCTURE("active-structure"),
    BEHAVIOR("behavior"),
    PASSIVE_STRUCTURE("passive-structure"),
    NONE("none");

    private final String code;

    Aspect(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Aspect fromCode(String code) {
        for (Aspect aspect : values()) {
            if (aspect.code.equals(code)) {
                return aspect;
            }
        }
        throw new IllegalArgumentException("unknown aspect code: " + code);
    }
}
