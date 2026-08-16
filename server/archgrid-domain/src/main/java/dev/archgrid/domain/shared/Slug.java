package dev.archgrid.domain.shared;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable, human-readable identifier for a modelling artifact.
 *
 * <p>Every workspace, model, element, relationship and view carries both a surrogate
 * UUID and a slug. The UUID is what the system references; the slug is what humans and
 * text formats reference. Renaming an element must never change its UUID, and — once
 * the Architecture-as-Code representation exists (phase 5) — should not change its slug
 * either, because the slug is what makes a Git diff of a model readable.
 *
 * <p>The constraints are deliberately narrow. A slug that may contain uppercase letters,
 * dots or slashes cannot be embedded in a file name, a URL path segment and a DSL
 * identifier without escaping, and every escaping rule is a future bug.
 */
public record Slug(String value) implements Comparable<Slug> {

    /** Long enough for {@code customer-facing-payment-orchestration-service}. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public Slug {
        Objects.requireNonNull(value, "slug value must not be null");
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "slug must be at most " + MAX_LENGTH + " characters, was " + value.length() + ": " + value);
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "slug must be lowercase alphanumeric segments separated by single hyphens, was: " + value);
        }
    }

    public static Slug of(String value) {
        return new Slug(value);
    }

    @Override
    public int compareTo(Slug other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
