package dev.archgrid.domain.profile;

/**
 * The data types a profile attribute may have.
 *
 * <p>These are exactly the six types of The Open Group exchange format's
 * {@code PropertyDefinitionType}, and the codes are its spelling. Inventing a seventh would
 * make a model unexportable, which is the one thing a customer evaluating an architecture
 * tool tests before buying.
 */
public enum AttributeType {
    STRING("string"),
    BOOLEAN("boolean"),
    CURRENCY("currency"),
    DATE("date"),
    TIME("time"),
    NUMBER("number");

    private final String code;

    AttributeType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static AttributeType fromCode(String code) {
        for (AttributeType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown attribute type code: " + code);
    }
}
