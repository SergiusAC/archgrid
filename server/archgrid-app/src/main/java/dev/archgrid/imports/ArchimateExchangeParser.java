package dev.archgrid.imports;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads an ArchiMate Model Exchange File into plain records, and nothing else.
 *
 * <p>No database, no commands, no opinions about what to keep — that is the import service's job
 * (ADR-0015). This class answers one question: what does the file say? It reads the shape the XSD
 * defines (ADR-0012): {@code <elements>}, {@code <relationships>}, {@code <organizations>},
 * {@code <propertyDefinitions>}, and {@code <views>} whose nodes carry geometry and whose
 * connections join <em>nodes</em>, not elements.
 *
 * <p>Namespace-tolerant on purpose: files in the wild declare the 3.0 namespace while validating
 * against the 3.1 schema (the sample this was built against does exactly that), so matching is by
 * local name.
 */
public final class ArchimateExchangeParser {

    private ArchimateExchangeParser() {}

    public record ParsedModel(
            String name,
            List<ParsedElement> elements,
            List<ParsedRelationship> relationships,
            Map<String, String> propertyDefinitions,
            List<ParsedFolder> folders,
            List<ParsedView> views) {}

    /** One {@code <element>}: its file-local id, concept, name, and whatever it carries. */
    public record ParsedElement(
            String id, String type, String name, String documentation, Map<String, String> properties) {}

    public record ParsedRelationship(
            String id,
            String type,
            String sourceId,
            String targetId,
            String name,
            Map<String, String> properties) {}

    /** A labelled {@code <item>}: a folder holding the referenced ids and child folders. */
    public record ParsedFolder(String label, List<String> memberIds, List<ParsedFolder> children) {}

    public record ParsedView(String id, String name, List<ParsedNode> nodes, List<ParsedConnection> connections) {}

    /**
     * One {@code <node>}. {@code elementRef} is null for a Label or a visual Container; nested
     * nodes carry their parent's file-local id.
     */
    public record ParsedNode(
            String id, String kind, String elementRef, String parentNodeId, double x, double y, double w, double h) {}

    /** One {@code <connection>}. Its ends are node ids; {@code relationshipRef} is null for a free Line. */
    public record ParsedConnection(
            String id, String kind, String relationshipRef, String sourceNodeId, String targetNodeId, List<double[]> bendpoints) {}

    public static ParsedModel parse(byte[] content) {
        Document document = read(content);
        Element root = document.getDocumentElement();

        Map<String, String> propertyDefinitions = new LinkedHashMap<>();
        for (Element definition : children(first(root, "propertyDefinitions"), "propertyDefinition")) {
            propertyDefinitions.put(definition.getAttribute("identifier"), text(first(definition, "name")));
        }

        List<ParsedElement> elements = new ArrayList<>();
        for (Element element : children(first(root, "elements"), "element")) {
            elements.add(new ParsedElement(
                    element.getAttribute("identifier"),
                    xsiType(element),
                    text(first(element, "name")),
                    text(first(element, "documentation")),
                    properties(element, propertyDefinitions)));
        }

        List<ParsedRelationship> relationships = new ArrayList<>();
        for (Element relationship : children(first(root, "relationships"), "relationship")) {
            Map<String, String> properties = properties(relationship, propertyDefinitions);
            // Attributes our model has no field for ride along as properties rather than being
            // dropped: `accessType` says whether an Access reads or writes, `isDirected` whether
            // an Association points. The report calls this out as interpreted.
            if (relationship.hasAttribute("accessType")) {
                properties.put("accessType", relationship.getAttribute("accessType"));
            }
            if (relationship.hasAttribute("isDirected")) {
                properties.put("isDirected", relationship.getAttribute("isDirected"));
            }
            relationships.add(new ParsedRelationship(
                    relationship.getAttribute("identifier"),
                    xsiType(relationship),
                    relationship.getAttribute("source"),
                    relationship.getAttribute("target"),
                    text(first(relationship, "name")),
                    properties));
        }

        List<ParsedFolder> folders = new ArrayList<>();
        Element organizations = first(root, "organizations");
        if (organizations != null) {
            for (Element item : children(organizations, "item")) {
                ParsedFolder folder = folder(item);
                if (folder != null) {
                    folders.add(folder);
                }
            }
        }

        List<ParsedView> views = new ArrayList<>();
        Element viewsElement = first(root, "views");
        Element diagrams = viewsElement == null ? null : first(viewsElement, "diagrams");
        for (Element view : children(diagrams, "view")) {
            List<ParsedNode> nodes = new ArrayList<>();
            collectNodes(view, null, nodes);
            List<ParsedConnection> connections = new ArrayList<>();
            for (Element connection : descendants(view, "connection")) {
                List<double[]> bendpoints = new ArrayList<>();
                for (Element bendpoint : children(connection, "bendpoint")) {
                    bendpoints.add(new double[] {
                        Double.parseDouble(bendpoint.getAttribute("x")), Double.parseDouble(bendpoint.getAttribute("y"))
                    });
                }
                connections.add(new ParsedConnection(
                        connection.getAttribute("identifier"),
                        xsiType(connection),
                        attribute(connection, "relationshipRef"),
                        connection.getAttribute("source"),
                        connection.getAttribute("target"),
                        bendpoints));
            }
            views.add(new ParsedView(
                    view.getAttribute("identifier"), text(first(view, "name")), nodes, connections));
        }

        return new ParsedModel(
                text(first(root, "name")), elements, relationships, propertyDefinitions, folders, views);
    }

    // ------------------------------------------------------------------ pieces

    /** A labelled item becomes a folder; an unlabelled top-level grouping of refs is not one. */
    private static ParsedFolder folder(Element item) {
        String label = text(first(item, "label"));
        List<String> members = new ArrayList<>();
        List<ParsedFolder> children = new ArrayList<>();
        for (Element child : children(item, "item")) {
            String ref = attribute(child, "identifierRef");
            if (ref != null) {
                members.add(ref);
            } else {
                ParsedFolder sub = folder(child);
                if (sub != null) {
                    children.add(sub);
                }
            }
        }
        if (label == null) {
            return null;
        }
        return new ParsedFolder(label, members, children);
    }

    private static void collectNodes(Element parent, String parentNodeId, List<ParsedNode> out) {
        for (Element node : children(parent, "node")) {
            String id = node.getAttribute("identifier");
            out.add(new ParsedNode(
                    id,
                    xsiType(node),
                    attribute(node, "elementRef"),
                    parentNodeId,
                    parseDouble(node, "x"),
                    parseDouble(node, "y"),
                    parseDouble(node, "w"),
                    parseDouble(node, "h")));
            collectNodes(node, id, out);
        }
    }

    private static Map<String, String> properties(Element owner, Map<String, String> definitions) {
        Map<String, String> properties = new LinkedHashMap<>();
        Element container = first(owner, "properties");
        for (Element property : children(container, "property")) {
            String name = definitions.get(property.getAttribute("propertyDefinitionRef"));
            String value = text(first(property, "value"));
            if (name != null && !name.isBlank() && value != null) {
                properties.put(name, value);
            }
        }
        return properties;
    }

    // ------------------------------------------------------------------ DOM plumbing

    private static Document read(byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // An imported file is untrusted input; entity expansion and external DTDs are how an
            // XML file becomes an attack rather than a model.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "This is not a readable ArchiMate exchange file: " + ex.getMessage(), ex);
        }
    }

    private static String xsiType(Element element) {
        String type = element.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type");
        // Strip a namespace prefix if the file used one (`archimate:BusinessActor`).
        int colon = type.indexOf(':');
        return colon >= 0 ? type.substring(colon + 1) : type;
    }

    private static Element first(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getLocalName().equals(localName)) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        if (parent == null) {
            return found;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getLocalName().equals(localName)) {
                found.add(element);
            }
        }
        return found;
    }

    private static List<Element> descendants(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList all = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < all.getLength(); i++) {
            found.add((Element) all.item(i));
        }
        return found;
    }

    private static String attribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static double parseDouble(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isBlank() ? 0 : Double.parseDouble(value);
    }

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }
}
