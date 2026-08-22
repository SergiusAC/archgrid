package dev.archgrid.imports;

import dev.archgrid.domain.shared.Id;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Archi's identifiers and ours are the same thing in two costumes.
 *
 * <p>Archi writes {@code id-} followed by 32 hex digits — a UUID with the dashes taken out, because
 * an {@code xs:ID} may not start with a digit. Reading that back into the UUID it always was means
 * an imported object keeps the identity its file gave it: a second import of the same file finds
 * it rather than duplicating it, and an export (phase 6b) prints the very identifier the file
 * arrived with (docs/09).
 *
 * <p>Not every file obeys: older Archi versions and hand-edited files carry eight- or
 * fourteen-character ids. Those get a fresh identity, and the report says how many — because for
 * them, and only them, a re-import cannot match by identity.
 */
final class ArchiIds {

    private ArchiIds() {}

    private static final Pattern ARCHI = Pattern.compile("id-([0-9a-fA-F]{32})");
    private static final Pattern CANONICAL = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    /** The identity an exchange-file identifier encodes, if it encodes one. */
    static Optional<Id> parse(String identifier) {
        if (identifier == null) {
            return Optional.empty();
        }
        String raw = identifier.strip();
        Matcher archi = ARCHI.matcher(raw);
        if (archi.matches()) {
            String hex = archi.group(1);
            return Optional.of(Id.parse(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                    + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20)));
        }
        if (CANONICAL.matcher(raw).matches()) {
            return Optional.of(Id.parse(raw));
        }
        return Optional.empty();
    }

    /** The identifier Archi would write for this identity — what an export prints. */
    static String print(Id id) {
        UUID value = id.value();
        return "id-" + value.toString().replace("-", "");
    }
}
