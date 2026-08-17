package dev.archgrid.domain.model;

import java.util.Objects;

/**
 * One reason a change was refused.
 *
 * <p>The {@code detail} is not decoration. Half of what an architecture tool is worth is
 * teaching the notation, and a validator that answers "invalid" teaches nothing — so every
 * violation is expected to say what was attempted and what would have been allowed instead
 * (ADR-0004). {@code code} exists so the client can react without parsing prose.
 *
 * @param subject the slug of the element or relationship at fault, for highlighting it
 */
public record Violation(String code, String subject, String detail) {

    public Violation {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }

    public static Violation of(Code code, Object subject, String detail) {
        return new Violation(code.code(), String.valueOf(subject), detail);
    }

    /** The failure modes worth distinguishing. */
    public enum Code {
        /** A relationship the metamodel does not permit between these two element types. */
        RELATIONSHIP_NOT_PERMITTED("relationship-not-permitted"),
        /** A type name that is neither an ArchiMate concept nor a profile in this model. */
        UNKNOWN_TYPE("unknown-type"),
        /** A command referred to an element or relationship that does not exist. */
        UNKNOWN_SUBJECT("unknown-subject"),
        /** A key already in use. */
        DUPLICATE_KEY("duplicate-key"),
        /** Containment that would make an element its own ancestor. */
        CONTAINMENT_CYCLE("containment-cycle"),
        /** A relationship whose source or target is not in the model. */
        DANGLING_ENDPOINT("dangling-endpoint"),
        /** A command that is well-formed but cannot apply to the current state. */
        NOT_APPLICABLE("not-applicable");

        private final String code;

        Code(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
