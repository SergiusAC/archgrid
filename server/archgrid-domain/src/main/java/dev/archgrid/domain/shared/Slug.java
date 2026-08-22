package dev.archgrid.domain.shared;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable, human-readable key for the things people name in URLs and configuration.
 *
 * <p>Since docs/09 this is <em>not</em> the identity of an element, relationship or view — those
 * carry one opaque {@link Id} each. What keeps a slug: the workspace and the model row, whose keys
 * are URL segments an administrator chooses, and profile attributes, whose keys are property names
 * in a file. Both are named by a person, stable by convention, and have to survive in a path or a
 * DSL without escaping — which is what the narrow shape below is for.
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
