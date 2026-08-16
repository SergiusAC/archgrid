package dev.archgrid.metamodel;

import dev.archgrid.domain.metamodel.ElementType;
import dev.archgrid.domain.metamodel.Layer;
import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.RelationshipType;
import dev.archgrid.domain.metamodel.c4.C4ElementRule;
import dev.archgrid.domain.metamodel.c4.C4Level;
import dev.archgrid.domain.metamodel.c4.C4Mapping;
import dev.archgrid.domain.metamodel.c4.C4RelationshipIntent;
import dev.archgrid.domain.metamodel.c4.C4Shape;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@code metamodel/c4-mapping.yaml} and validates it against the loaded ArchiMate
 * metamodel.
 *
 * <p>Unlike the metamodel itself this file is hand-authored, which makes validation more
 * important rather than less: a typo here does not fail, it produces a C4 view that is
 * quietly missing a box.
 */
public final class C4MappingLoader {

    private C4MappingLoader() {}

    public static C4Mapping load(InputStream source, Metamodel metamodel) {
        Map<String, Object> root = parse(source);
        requireAppliesTo(root, metamodel);

        C4Mapping.Builder builder = C4Mapping.builder();

        Map<String, Object> rendering = map(root, "rendering");
        rendering.forEach((shapeCode, concepts) -> {
            C4Shape shape = C4Shape.fromCode(shapeCode);
            for (String concept : stringList(concepts, "rendering." + shapeCode)) {
                builder.shape(concept, shape);
            }
        });

        for (Map<String, Object> entry : mapList(root, "levels")) {
            Set<Layer> layers = new LinkedHashSet<>();
            for (String code : stringList(entry.get("layers"), "levels.layers")) {
                layers.add(Layer.fromCode(code));
            }
            builder.level(new C4Level(
                    string(entry, "code"), string(entry, "name"), integer(entry, "depth"), layers));
        }

        for (Map<String, Object> entry : mapList(root, "authoring")) {
            builder.authoringRule(new C4ElementRule(
                    string(entry, "c4"),
                    elementType(metamodel, string(entry, "concept")),
                    readDiscriminators(entry, metamodel)));
        }

        Map<String, Object> relationships = map(root, "relationships");
        builder.defaultIntent(String.valueOf(relationships.get("default")));
        for (Map<String, Object> entry : toMapList(relationships.get("intents"), "relationships.intents")) {
            builder.intent(new C4RelationshipIntent(
                    string(entry, "intent"),
                    string(entry, "description"),
                    relationshipType(metamodel, string(entry, "concept")),
                    bool(entry, "reverse")));
        }

        return builder.build(metamodel);
    }

    private static List<C4ElementRule.Discriminator> readDiscriminators(
            Map<String, Object> entry, Metamodel metamodel) {
        Object raw = entry.get("discriminators");
        if (raw == null) {
            return List.of();
        }
        List<C4ElementRule.Discriminator> discriminators = new ArrayList<>();
        for (Map<String, Object> item : toMapList(raw, "discriminators")) {
            discriminators.add(new C4ElementRule.Discriminator(
                    elementType(metamodel, string(item, "concept")),
                    stringList(item.get("technologyMatches"), "technologyMatches")));
        }
        return discriminators;
    }

    /**
     * Refuses a mapping written for a different language version.
     *
     * <p>Cheap, and it turns "the palette looks odd since the upgrade" into a startup failure
     * with the reason in the message.
     */
    private static void requireAppliesTo(Map<String, Object> root, Metamodel metamodel) {
        Map<String, Object> appliesTo = map(root, "appliesTo");
        String language = String.valueOf(appliesTo.get("language"));
        String version = String.valueOf(appliesTo.get("version"));
        if (!metamodel.language().equals(language) || !metamodel.version().equals(version)) {
            throw new IllegalStateException("the C4 mapping declares " + language + " " + version
                    + " but the loaded metamodel is " + metamodel.language() + " " + metamodel.version());
        }
    }

    private static ElementType elementType(Metamodel metamodel, String concept) {
        return metamodel.elementType(concept)
                .orElseThrow(() -> new IllegalStateException(
                        "C4 mapping references unknown element type: " + concept));
    }

    private static RelationshipType relationshipType(Metamodel metamodel, String concept) {
        return metamodel.relationshipType(concept)
                .orElseThrow(() -> new IllegalStateException(
                        "C4 mapping references unknown relationship type: " + concept));
    }

    // ------------------------------------------------------------------ yaml plumbing

    private static Map<String, Object> parse(InputStream source) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(1024 * 1024);
        Object loaded = new Yaml(new SafeConstructor(options)).load(source);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalStateException("the C4 mapping must be a YAML mapping at the top level");
        }
        return toStringKeyed(map);
    }

    private static Map<String, Object> toStringKeyed(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
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

    private static boolean bool(Map<String, Object> holder, String key) {
        Object value = holder.get(key);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalStateException("key '" + key + "' must be true or false, was " + value);
        }
        return flag;
    }

    private static Map<String, Object> map(Map<String, Object> holder, String key) {
        Object value = holder.get(key);
        if (!(value instanceof Map<?, ?> nested)) {
            throw new IllegalStateException("key '" + key + "' must be a mapping");
        }
        return toStringKeyed(nested);
    }

    private static List<Map<String, Object>> mapList(Map<String, Object> holder, String key) {
        return toMapList(holder.get(key), key);
    }

    private static List<Map<String, Object>> toMapList(Object raw, String where) {
        if (!(raw instanceof List<?> items)) {
            throw new IllegalStateException("'" + where + "' must be a list");
        }
        List<Map<String, Object>> result = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalStateException("entries of '" + where + "' must be mappings");
            }
            result.add(toStringKeyed(entry));
        }
        return result;
    }

    private static List<String> stringList(Object raw, String where) {
        if (!(raw instanceof List<?> items)) {
            throw new IllegalStateException("'" + where + "' must be a list");
        }
        return items.stream().map(String::valueOf).toList();
    }
}
