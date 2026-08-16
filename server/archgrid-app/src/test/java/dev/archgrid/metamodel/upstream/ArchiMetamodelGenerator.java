package dev.archgrid.metamodel.upstream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Builds {@code metamodel/archimate-3.2.yaml} from the vendored Archi sources.
 *
 * <p>Run with {@code ./gradlew generateMetamodel}. {@code MetamodelGenerationTest} runs the
 * same code and fails if the committed file differs, so the generated artefact cannot drift
 * away from its inputs unnoticed.
 *
 * <p>Everything emitted is derived mechanically from upstream — layer and aspect from the
 * {@code eSuperTypes} hierarchy in {@code archimate.ecore}, the matrix from
 * {@code relationships.xml}, palettes from {@code viewpoints.xml} with macros expanded
 * against the same hierarchy. The single exception is the structural strength ordering, which
 * upstream does not encode; see {@link #STRUCTURAL_STRENGTH}.
 */
public final class ArchiMetamodelGenerator {

    private static final String UPSTREAM_COMMIT = "ccb3fd650f23acc6d5f3ad77fa9de809a5fc2f5a";

    /**
     * The one value in this file that does not come from upstream.
     *
     * <p>Archi encodes which relationships are structural but not their relative strength, and
     * the weakest-link derivation rule needs an order. This is the conventional ordering and it
     * is <strong>not verified against the specification</strong> — the generated file says so
     * too. It affects derived relationships only (phase 4), never plain validation.
     */
    private static final List<String> STRUCTURAL_STRENGTH = List.of("Composition", "Aggregation", "Assignment", "Realization");

    /** Concepts that appear in the matrix but are not elements and are absent from the ecore. */
    private static final Set<String> PSEUDO_CONCEPTS = Set.of("Relationship");

    private static final Map<String, String> DISPLAY_NAME_OVERRIDES = Map.of("CourseOfAction", "Course of Action");

    private final Path upstreamDir;

    ArchiMetamodelGenerator(Path upstreamDir) {
        this.upstreamDir = upstreamDir;
    }

    public static void main(String[] args) throws IOException {
        Path root = repositoryRoot();
        Path target = root.resolve("metamodel").resolve("archimate-3.2.yaml");
        String yaml = new ArchiMetamodelGenerator(root.resolve("metamodel/upstream/archi")).generate();
        Files.writeString(target, yaml, StandardCharsets.UTF_8);
        System.out.println("wrote " + target + " (" + yaml.lines().count() + " lines)");
    }

    /** Walks up from the working directory until the vendored upstream directory is found. */
    static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("metamodel/upstream/archi"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from " + Path.of("").toAbsolutePath());
    }

    String generate() {
        Document ecore = parse(upstreamDir.resolve("archimate.ecore"));
        Document keys = parse(upstreamDir.resolve("relationships-keys.xml"));
        Document matrix = parse(upstreamDir.resolve("relationships.xml"));
        Document viewpoints = parse(upstreamDir.resolve("viewpoints.xml"));

        Map<String, Set<String>> supertypes = readSupertypeClosure(ecore);
        Map<Character, String> relationshipByKey = readRelationshipKeys(keys);
        Map<String, Map<String, String>> permitted = readMatrix(matrix);
        String version = matrix.getDocumentElement().getAttribute("version");

        List<String> concepts = permitted.keySet().stream().sorted().toList();
        verifyConceptsAreKnown(concepts, supertypes);

        StringBuilder out = new StringBuilder(512 * 1024);
        writeHeader(out, version);
        writeRelationshipTypes(out, relationshipByKey, supertypes);
        writeElementTypes(out, concepts, supertypes);
        writeViewpoints(out, viewpoints, concepts, supertypes);
        writePermitted(out, permitted);
        return out.toString();
    }

    // ------------------------------------------------------------------ upstream readers

    private static Map<String, Set<String>> readSupertypeClosure(Document ecore) {
        Map<String, List<String>> direct = new LinkedHashMap<>();
        NodeList classifiers = ecore.getElementsByTagName("eClassifiers");
        for (int i = 0; i < classifiers.getLength(); i++) {
            Element classifier = (Element) classifiers.item(i);
            String name = classifier.getAttribute("name");
            if (name.isEmpty()) {
                continue;
            }
            List<String> parents = new ArrayList<>();
            String raw = classifier.getAttribute("eSuperTypes");
            if (!raw.isBlank()) {
                for (String token : raw.trim().split("\\s+")) {
                    parents.add(token.replace("#//", ""));
                }
            }
            direct.put(name, parents);
        }

        Map<String, Set<String>> closure = new LinkedHashMap<>();
        for (String name : direct.keySet()) {
            Set<String> reached = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>(direct.getOrDefault(name, List.of()));
            while (!queue.isEmpty()) {
                String parent = queue.poll();
                if (reached.add(parent)) {
                    queue.addAll(direct.getOrDefault(parent, List.of()));
                }
            }
            closure.put(name, reached);
        }
        return closure;
    }

    private static Map<Character, String> readRelationshipKeys(Document keys) {
        Map<Character, String> byKey = new LinkedHashMap<>();
        NodeList nodes = keys.getElementsByTagName("key");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element key = (Element) nodes.item(i);
            String character = key.getAttribute("char");
            if (character.length() != 1) {
                throw new IllegalStateException("relationship key must be a single character: " + character);
            }
            byKey.put(character.charAt(0), key.getAttribute("relationship").replaceAll("Relationship$", ""));
        }
        return byKey;
    }

    /** source concept -&gt; target concept -&gt; the letter codes permitted between them. */
    private static Map<String, Map<String, String>> readMatrix(Document matrix) {
        Map<String, Map<String, String>> permitted = new TreeMap<>();
        NodeList sources = matrix.getElementsByTagName("source");
        for (int i = 0; i < sources.getLength(); i++) {
            Element source = (Element) sources.item(i);
            Map<String, String> row = new TreeMap<>();
            NodeList targets = source.getElementsByTagName("target");
            for (int j = 0; j < targets.getLength(); j++) {
                Element target = (Element) targets.item(j);
                row.put(target.getAttribute("concept"), sortLetters(target.getAttribute("relations")));
            }
            permitted.put(source.getAttribute("concept"), row);
        }
        return permitted;
    }

    private static String sortLetters(String letters) {
        char[] chars = letters.toCharArray();
        java.util.Arrays.sort(chars);
        return new String(chars);
    }

    private static void verifyConceptsAreKnown(List<String> concepts, Map<String, Set<String>> supertypes) {
        List<String> unknown = concepts.stream()
                .filter(concept -> !supertypes.containsKey(concept) && !PSEUDO_CONCEPTS.contains(concept))
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "the matrix references concepts absent from archimate.ecore and not known pseudo-concepts: "
                            + unknown);
        }
    }

    // ------------------------------------------------------------------ classification

    private static String layerOf(String concept, Map<String, Set<String>> supertypes) {
        Set<String> parents = supertypes.getOrDefault(concept, Set.of());
        // Order matters: physical elements are also technology elements upstream, and strategy
        // behaviour elements are also behaviour elements. The most specific layer wins.
        if (parents.contains("PhysicalElement")) {
            return "physical";
        }
        if (parents.contains("StrategyElement")) {
            return "strategy";
        }
        if (parents.contains("MotivationElement")) {
            return "motivation";
        }
        if (parents.contains("ImplementationMigrationElement")) {
            return "implementation-migration";
        }
        if (parents.contains("BusinessElement")) {
            return "business";
        }
        if (parents.contains("ApplicationElement")) {
            return "application";
        }
        if (parents.contains("TechnologyElement")) {
            return "technology";
        }
        return "other";
    }

    private static String aspectOf(String concept, Map<String, Set<String>> supertypes) {
        Set<String> parents = supertypes.getOrDefault(concept, Set.of());
        if (parents.contains("ActiveStructureElement")) {
            return "active-structure";
        }
        if (parents.contains("BehaviorElement")) {
            return "behavior";
        }
        if (parents.contains("PassiveStructureElement")) {
            return "passive-structure";
        }
        return "none";
    }

    private static String categoryOf(String relationshipConcept, Map<String, Set<String>> supertypes) {
        Set<String> parents = supertypes.getOrDefault(relationshipConcept + "Relationship", Set.of());
        if (parents.contains("StructuralRelationship")) {
            return "structural";
        }
        // Spelled with the typo it carries upstream. Matching the real data beats matching the
        // word we wish were there.
        if (parents.contains("DependendencyRelationship")) {
            return "dependency";
        }
        if (parents.contains("DynamicRelationship")) {
            return "dynamic";
        }
        return "other";
    }

    static String displayName(String concept) {
        String override = DISPLAY_NAME_OVERRIDES.get(concept);
        if (override != null) {
            return override;
        }
        return concept.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
    }

    // ------------------------------------------------------------------ emitters

    private void writeHeader(StringBuilder out, String version) {
        // Kept strictly ASCII: this file is diffed, parsed and shipped, and a stray non-ASCII
        // character is a needless way for one of those three to go wrong.
        out.append("# ArchGrid canonical metamodel - GENERATED, do not edit by hand.\n")
                .append("# Regenerate with: ./gradlew generateMetamodel\n")
                .append("#\n")
                .append("# Derived from the vendored Archi sources in metamodel/upstream/archi.\n")
                .append("# Verification status is recorded in metamodel/VERIFICATION.md: the vocabulary is\n")
                .append("# verified against The Open Group exchange XSD; the matrix, the structural strength\n")
                .append("# ordering and the viewpoint palettes are accepted on the authority of Archi.\n")
                .append("language: archimate\n")
                .append("version: \"")
                .append(version)
                .append("\"\n")
                .append("source:\n")
                .append("  upstream: archi\n")
                .append("  commit: ")
                .append(UPSTREAM_COMMIT)
                .append("\n")
                .append("  files:\n");
        for (String file : List.of("archimate.ecore", "relationships-keys.xml", "relationships.xml", "viewpoints.xml")) {
            out.append("    ").append(file).append(": ").append(sha256(upstreamDir.resolve(file))).append("\n");
        }
        out.append("\n");
    }

    private static void writeRelationshipTypes(
            StringBuilder out, Map<Character, String> byKey, Map<String, Set<String>> supertypes) {
        out.append("# The eleven ArchiMate relationship types. 'key' is the letter used in the\n")
                .append("# permitted matrix below, kept so this file stays diffable against upstream.\n")
                .append("# 'strength' orders structural types for the weakest-link derivation rule, 1 being\n")
                .append("# strongest; it is the only value here not taken from upstream and is UNVERIFIED.\n")
                .append("relationshipTypes:\n");
        List<Map.Entry<Character, String>> entries = new ArrayList<>(byKey.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getValue));
        for (Map.Entry<Character, String> entry : entries) {
            String concept = entry.getValue();
            String category = categoryOf(concept, supertypes);
            int strength = STRUCTURAL_STRENGTH.indexOf(concept) + 1;
            if ("structural".equals(category) == (strength == 0)) {
                throw new IllegalStateException(
                        "structural types and the strength ordering disagree about " + concept);
            }
            // 'key' is quoted deliberately: YAML 1.1 resolves several bare single letters as
            // booleans, and a relationship key silently becoming `false` is the kind of defect
            // that surfaces months later as one missing row in a validation table.
            out.append("  - concept: ").append(concept).append("\n")
                    .append("    name: \"").append(displayName(concept)).append("\"\n")
                    .append("    key: \"").append(entry.getKey()).append("\"\n")
                    .append("    category: ").append(category).append("\n")
                    .append("    strength: ").append(strength).append("\n");
        }
        out.append("\n");
    }

    private static void writeElementTypes(
            StringBuilder out, List<String> concepts, Map<String, Set<String>> supertypes) {
        out.append("# Element types with layer and aspect derived from the upstream eSuperTypes\n")
                .append("# hierarchy. 'Junction' and 'Relationship' are not elements: they appear because\n")
                .append("# the matrix needs endpoints for relationships that target relationships.\n")
                .append("elementTypes:\n");
        for (String concept : concepts) {
            out.append("  - concept: ").append(concept).append("\n")
                    .append("    name: \"").append(displayName(concept)).append("\"\n")
                    .append("    layer: ").append(layerOf(concept, supertypes)).append("\n")
                    .append("    aspect: ").append(aspectOf(concept, supertypes)).append("\n");
        }
        out.append("\n");
    }

    private static void writeViewpoints(
            StringBuilder out, Document viewpoints, List<String> concepts, Map<String, Set<String>> supertypes) {
        out.append("# The 25 standard viewpoints. Upstream palettes contain macros such as\n")
                .append("# $ApplicationElements$; they are expanded here against the same type hierarchy.\n")
                .append("# An empty concept list means every concept is allowed (the Layered viewpoint).\n")
                .append("viewpoints:\n");
        NodeList nodes = viewpoints.getElementsByTagName("viewpoint");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element viewpoint = (Element) nodes.item(i);
            Set<String> palette = new LinkedHashSet<>();
            NodeList conceptNodes = viewpoint.getElementsByTagName("concept");
            for (int j = 0; j < conceptNodes.getLength(); j++) {
                String token = conceptNodes.item(j).getTextContent().trim();
                if (token.startsWith("$") && token.endsWith("$")) {
                    palette.addAll(expandMacro(token, concepts, supertypes));
                } else {
                    palette.add(token);
                }
            }
            List<String> sorted = palette.stream().sorted().toList();
            out.append("  - code: ").append(viewpoint.getAttribute("id")).append("\n")
                    .append("    name: \"").append(textOfFirst(viewpoint, "name")).append("\"\n")
                    .append("    concepts: [").append(String.join(", ", sorted)).append("]\n");
        }
        out.append("\n");
    }

    /**
     * Expands {@code $ApplicationElements$} to every concept that is an {@code ApplicationElement}
     * upstream.
     *
     * <p>Resolved against the type hierarchy rather than against our own layer enum on purpose:
     * physical elements are also technology elements upstream, and reproducing that faithfully is
     * what keeps our palettes identical to the ones architects already know.
     */
    private static Set<String> expandMacro(String macro, List<String> concepts, Map<String, Set<String>> supertypes) {
        String plural = macro.substring(1, macro.length() - 1);
        if (!plural.endsWith("s")) {
            throw new IllegalStateException("unrecognised viewpoint macro: " + macro);
        }
        String supertype = plural.substring(0, plural.length() - 1);
        Set<String> expanded = new LinkedHashSet<>();
        for (String concept : concepts) {
            if (supertypes.getOrDefault(concept, Set.of()).contains(supertype)) {
                expanded.add(concept);
            }
        }
        if (expanded.isEmpty()) {
            throw new IllegalStateException("viewpoint macro " + macro + " expanded to nothing; supertype '" + supertype
                    + "' is not in the hierarchy");
        }
        return expanded;
    }

    private static void writePermitted(StringBuilder out, Map<String, Map<String, String>> permitted) {
        out.append("# The permitted-relationship matrix, source concept -> target concept -> the\n")
                .append("# relationship type keys allowed between them, letters sorted alphabetically.\n")
                .append("# This answers 'may such a relationship exist at all'. It merges direct and\n")
                .append("# derivable relationships and therefore cannot say whether a given relationship in\n")
                .append("# a model is explicit or derived: that is computed from the model graph.\n")
                .append("#\n")
                .append("# Values are quoted. Unquoted, the perfectly legitimate cell value \"no\"\n")
                .append("# (Influence + Association) is parsed by YAML 1.1 as the boolean false.\n")
                .append("permitted:\n");
        permitted.forEach((source, row) -> {
            out.append("  ").append(source).append(":\n");
            row.forEach((target, letters) -> out.append("    ")
                    .append(target)
                    .append(": \"")
                    .append(letters)
                    .append("\"\n"));
        });
    }

    // ------------------------------------------------------------------ helpers

    private static String textOfFirst(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getParentNode() == parent) {
                return node.getTextContent().trim();
            }
        }
        throw new IllegalStateException("no <" + tag + "> under " + parent.getAttribute("id"));
    }

    private static Document parse(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // These inputs are vendored and trusted, but a parser that resolves external
            // entities is a habit worth not having.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(file.toFile());
        } catch (ParserConfigurationException | SAXException ex) {
            throw new IllegalStateException("could not parse " + file, ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not read " + file, ex);
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not read " + file, ex);
        }
    }
}
