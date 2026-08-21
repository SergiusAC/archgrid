package dev.archgrid.imports;

import dev.archgrid.domain.shared.Slug;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Makes slugs out of the names an exchange file carries.
 *
 * <p>File ids are opaque and names are whatever language the architect works in — the sample this
 * importer was built against is Russian throughout. A slug of hyphens where the letters used to be
 * defeats the whole point of slugs (readable command logs, diffable DSL), so Cyrillic is
 * transliterated rather than stripped. Latin diacritics fold via Unicode decomposition.
 *
 * <p>Names in a model are not unique — the sample has 91 duplicates — so {@link #unique} suffixes
 * on collision and records what it handed out. Bases stop at {@value #BASE_LENGTH} characters to
 * leave room for those suffixes under {@link Slug#MAX_LENGTH}.
 */
final class Slugs {

    private Slugs() {}

    /** Room for {@code -999} and then some before {@link Slug#MAX_LENGTH} objects. */
    private static final int BASE_LENGTH = 56;

    private static final Map<Character, String> CYRILLIC = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"),
            Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('ё', "e"), Map.entry('ж', "zh"),
            Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('й', "y"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"),
            Map.entry('п', "p"), Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"),
            Map.entry('у', "u"), Map.entry('ф', "f"), Map.entry('х', "kh"), Map.entry('ц', "ts"),
            Map.entry('ч', "ch"), Map.entry('ш', "sh"), Map.entry('щ', "shch"), Map.entry('ъ', ""),
            Map.entry('ы', "y"), Map.entry('ь', ""), Map.entry('э', "e"), Map.entry('ю', "yu"),
            Map.entry('я', "ya"));

    /**
     * A free slug for {@code name}, or for {@code fallback} when the name yields nothing.
     * The result is added to {@code taken} so the next call cannot hand it out again.
     */
    static String unique(String name, String fallback, Set<String> taken) {
        String base = slugify(name);
        if (base.isEmpty()) {
            base = slugify(fallback);
        }
        String candidate = base;
        for (int suffix = 2; taken.contains(candidate); suffix++) {
            candidate = base + "-" + suffix;
        }
        taken.add(candidate);
        return candidate;
    }

    /** The concept's own name in kebab case — what a nameless element falls back to. */
    static String fallbackFor(String concept) {
        return slugify(concept.replaceAll("(?<=[a-z])(?=[A-Z])", " "));
    }

    static String slugify(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder romanised = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            String replacement = CYRILLIC.get(c);
            romanised.append(replacement != null ? replacement : c);
        }
        String folded = Normalizer.normalize(romanised, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = folded.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > BASE_LENGTH) {
            slug = slug.substring(0, BASE_LENGTH).replaceAll("-+$", "");
            // Never end mid-word when a hyphen is near enough to cut at instead.
            int hyphen = slug.lastIndexOf('-');
            if (hyphen > BASE_LENGTH - 16) {
                slug = slug.substring(0, hyphen);
            }
        }
        return slug;
    }
}
