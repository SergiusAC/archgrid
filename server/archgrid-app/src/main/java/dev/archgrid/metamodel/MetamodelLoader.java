package dev.archgrid.metamodel;

import dev.archgrid.domain.metamodel.Aspect;
import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Layer;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipCategory;
import dev.archgrid.domain.metamodel.RelationshipType;
import dev.archgrid.domain.metamodel.Viewpoint;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads the canonical metamodel file into a {@link Metamodel}.
 *
 * <p>Strict by design. Every failure mode here — an unknown layer code, a relationship key that
 * no type declares, a matrix row pointing at an undeclared concept — is thrown rather than
 * skipped. A metamodel that loads with holes in it produces a validator that permits things it
 * should refuse, and that failure is invisible until an architect trusts a wrong answer.
 */
public final class MetamodelLoader {

    private MetamodelLoader() {}

    public static Metamodel load(InputStream source) {
        Map<String, Object> root = parse(source);

        Metamodel.Builder builder = Metamodel.builder()
                .language(string(root, "language"))
                .version(string(root, "version"));

        Map<Character, RelationshipType> byKey = new LinkedHashMap<>();
        for (Map<String, Object> entry : list(root, "relationshipTypes")) {
            RelationshipType type = readRelationshipType(entry);
            String key = string(entry, "key");
            if (key.length() != 1) {
                throw new IllegalStateException("relationship key must be one character, was '" + key + "'");
            }
            if (byKey.put(key.charAt(0), type) != null) {
                throw new IllegalStateException("duplicate relationship key: " + key);
            }
            builder.relationshipType(type);
        }

        for (Map<String, Object> entry : list(root, "elementTypes")) {
            builder.elementType(new ElementType(
                    string(entry, "concept"),
                    string(entry, "name"),
                    Layer.fromCode(string(entry, "layer")),
                    Aspect.fromCode(string(entry, "aspect"))));
        }

        for (Map<String, Object> entry : list(root, "viewpoints")) {
            builder.viewpoint(new Viewpoint(
                    string(entry, "code"),
                    string(entry, "name"),
                    new java.util.LinkedHashSet<>(strings(entry, "concepts"))));
        }

        Map<String, Object> permitted = map(root, "permitted");
        permitted.forEach((source1, row) -> {
            if (!(row instanceof Map<?, ?> targets)) {
                throw new IllegalStateException("permitted." + source1 + " must be a mapping of target to keys");
            }
            targets.forEach((target, letters) ->
                    builder.permit(source1, String.valueOf(target), decode(String.valueOf(letters), byKey, source1, target)));
        });

        // Metamodel.build() performs the cross-reference checks; anything it rejects is a
        // corrupt file, not a recoverable condition.
        return builder.build();
    }

    private static RelationshipType readRelationshipType(Map<String, Object> entry) {
        return new RelationshipType(
                string(entry, "concept"),
                string(entry, "name"),
                string(entry, "key").charAt(0),
                RelationshipCategory.fromCode(string(entry, "category")),
                integer(entry, "strength"));
    }

    private static List<RelationshipType> decode(
            String letters, Map<Character, RelationshipType> byKey, Object source, Object target) {
        List<RelationshipType> types = new ArrayList<>(letters.length());
        for (char letter : letters.toCharArray()) {
            RelationshipType type = byKey.get(letter);
            if (type == null) {
                throw new IllegalStateException(
                        "permitted." + source + "." + target + " uses relationship key '" + letter
                                + "' which no relationship type declares");
            }
            types.add(type);
        }
        return types;
    }

    // ------------------------------------------------------------------ yaml plumbing

    private static Map<String, Object> parse(InputStream source) {
        LoaderOptions options = new LoaderOptions();
        // The file is ~105 KB of nested mappings; the defaults are ample, but an explicit
        // ceiling keeps a corrupted file from being an out-of-memory condition.
        options.setCodePointLimit(8 * 1024 * 1024);
        Object loaded = new Yaml(new SafeConstructor(options)).load(source);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalStateException("metamodel file must be a YAML mapping at the top level");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        map.forEach((key, value) -> root.put(String.valueOf(key), value));
        return root;
    }

    private static String string(Map<String, Object> holder, String key) {
        Object value = holder.get(key);
        if (value == null) {
            throw new IllegalStateException("missing required key '" + key + "'");
        }
        return String.valueOf(value);
    }

    private static int integer(Map<String, Object> holder, String key) {
        Object value = holder.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("key '" + key + "' must be a number, was " + value);
        }
        return number.intValue();
    }

    private static Map<String, Object> map(Map<String, Object> holder, String key) {
        Object value = holder.get(key);
        if (!(value instanceof Map<?, ?> nested)) {
            throw new IllegalStateException("key '" + key + "' must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        nested.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static List<Map<String, Object>> list(Map<String, Object> holder, String key) {
        Object value = holder.get(key);
        if (!(value instanceof List<?> items)) {
            throw new IllegalStateException("key '" + key + "' must be a list");
        }
        List<Map<String, Object>> result = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalStateException("entries of '" + key + "' must be mappings");
            }
            Map<String, Object> converted = new LinkedHashMap<>();
            entry.forEach((k, v) -> converted.put(String.valueOf(k), v));
            result.add(converted);
        }
        return result;
    }

    private static List<String> strings(Map<String, Object> holder, String key) {
        Object value = holder.get(key);
        if (!(value instanceof List<?> items)) {
            throw new IllegalStateException("key '" + key + "' must be a list");
        }
        return items.stream().map(String::valueOf).toList();
    }
}
