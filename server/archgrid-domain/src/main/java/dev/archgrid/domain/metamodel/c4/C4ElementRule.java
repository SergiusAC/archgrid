package dev.archgrid.domain.metamodel.c4;

import dev.archgrid.domain.metamodel.ElementType;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What ArchiMate element type to create for a C4 concept.
 *
 * <p>One-to-many in this direction, which is why discriminators exist. C4 calls an
 * application, a relational database and a message broker all "containers"; ArchiMate does
 * not, and picking {@code ApplicationComponent} for a PostgreSQL instance produces a model
 * that validates but says something false.
 *
 * @param discriminators tried in order against C4's free-text technology field; first match
 *     wins, and {@code defaultType} applies when none does
 */
public record C4ElementRule(String c4Concept, ElementType defaultType, List<Discriminator> discriminators) {

    public C4ElementRule {
        Objects.requireNonNull(c4Concept, "c4Concept must not be null");
        Objects.requireNonNull(defaultType, "defaultType must not be null");
        Objects.requireNonNull(discriminators, "discriminators must not be null");
        discriminators = List.copyOf(discriminators);
    }

    /**
     * Resolves the ArchiMate type for a C4 element.
     *
     * @param technology C4's technology annotation, e.g. {@code "PostgreSQL 16"}. May be null
     *     or blank, in which case the default applies.
     */
    public ElementType resolve(String technology) {
        if (technology == null || technology.isBlank()) {
            return defaultType;
        }
        String needle = technology.toLowerCase(Locale.ROOT);
        for (Discriminator discriminator : discriminators) {
            if (discriminator.matches(needle)) {
                return discriminator.type();
            }
        }
        return defaultType;
    }

    /** @param technologyMatches lowercase substrings; matching is case-insensitive containment */
    public record Discriminator(ElementType type, List<String> technologyMatches) {

        public Discriminator {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(technologyMatches, "technologyMatches must not be null");
            if (technologyMatches.isEmpty()) {
                throw new IllegalArgumentException("a discriminator with no matches would never fire: " + type);
            }
            technologyMatches = technologyMatches.stream()
                    .map(match -> match.toLowerCase(Locale.ROOT))
                    .toList();
        }

        boolean matches(String lowercaseTechnology) {
            return technologyMatches.stream().anyMatch(lowercaseTechnology::contains);
        }
    }
}
