package dev.schemabridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.net.URI;
import javax.xml.namespace.QName;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class SchemaMerger {
    private static final String XSD = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final List<String> MAPPING_ATTRIBUTES = List.of(
            "type", "ref", "minOccurs", "maxOccurs", "nillable", "use", "form");

    private SchemaMerger() {
    }

    static String targetNamespace(Path schema) throws Exception {
        return parse(schema).getDocumentElement().getAttribute("targetNamespace");
    }

    static List<Path> dependencies(Path schema) throws Exception {
        Path rootSchema = schema.toAbsolutePath().normalize();
        List<Path> schemas = new ArrayList<>();
        collectDependencies(rootSchema, rootSchema.getParent(), new HashSet<>(), schemas);
        return schemas;
    }

    static Path findSchemaForNamespace(Path directory, String namespace) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".xsd")).toList()) {
                if (Objects.equals(namespace, targetNamespace(path))) {
                    return path;
                }
            }
        }
        return null;
    }

    private static void collectDependencies(Path schema, Path rootDirectory, Set<Path> visited,
            List<Path> schemas) throws Exception {
        Path normalizedSchema = schema.toAbsolutePath().normalize();
        if (!visited.add(normalizedSchema)) {
            return;
        }
        schemas.add(normalizedSchema);
        Element schemaRoot = parse(normalizedSchema).getDocumentElement();
        for (Element dependency : directChildren(schemaRoot, null)) {
            if (!List.of("import", "include").contains(dependency.getLocalName())) {
                continue;
            }
            String location = dependency.getAttribute("schemaLocation");
            if (location.isBlank()) {
                continue;
            }
            Path dependencyPath;
            try {
                URI locationUri = URI.create(location);
                if (locationUri.isAbsolute()) {
                    continue;
                }
                dependencyPath = normalizedSchema.getParent().resolve(locationUri.getPath()).normalize();
            } catch (IllegalArgumentException exception) {
                dependencyPath = normalizedSchema.getParent().resolve(location).normalize();
            }
            if (!dependencyPath.startsWith(rootDirectory)) {
                throw new IOException("Schema dependency resolves outside schema directory: " + dependencyPath);
            }
            if (!Files.isRegularFile(dependencyPath)) {
                throw new IOException("Schema dependency not found: " + dependencyPath);
            }
            collectDependencies(dependencyPath, rootDirectory, visited, schemas);
        }
    }

    static void merge(Path originalPath, Path generatedPath, Path outputPath) throws Exception {
        Document original = parse(originalPath);
        Document generated = parse(generatedPath);
        Element originalRoot = original.getDocumentElement();
        Element generatedRoot = generated.getDocumentElement();
        if (!originalRoot.getAttribute("targetNamespace").equals(generatedRoot.getAttribute("targetNamespace"))) {
            throw new IOException("Generated schema namespace does not match the original schema");
        }

        copyNamespaceDeclarations(originalRoot, generatedRoot);
        mergeNamedDeclarations(original, originalRoot, generatedRoot, "simpleType", SchemaMerger::mergeSimpleType);
        mergeNamedDeclarations(original, originalRoot, generatedRoot, "complexType", SchemaMerger::mergeComplexType);
        mergeNamedDeclarations(original, originalRoot, generatedRoot, "element", SchemaMerger::mergeDeclaration);
        mergeNamedDeclarations(original, originalRoot, generatedRoot, "attribute", SchemaMerger::mergeDeclaration);
        removeFormattingWhitespace(originalRoot, false);
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.transform(new DOMSource(original), new StreamResult(outputPath.toFile()));
    }

    private static void mergeNamedDeclarations(Document document, Element originalRoot, Element generatedRoot,
            String localName, DeclarationMerger merger) throws Exception {
        for (Element generatedDeclaration : directChildren(generatedRoot, localName)) {
            String name = generatedDeclaration.getAttribute("name");
            Element originalDeclaration = directChildren(originalRoot, localName).stream()
                    .filter(candidate -> candidate.getAttribute("name").equals(name))
                    .findFirst().orElse(null);
            if (originalDeclaration == null) {
                originalRoot.appendChild(importElement(document, generatedDeclaration, generatedRoot));
            } else {
                merger.merge(document, originalDeclaration, generatedDeclaration, generatedRoot);
            }
        }
    }

    private static void mergeSimpleType(Document document, Element original, Element generated, Element generatedRoot)
            throws Exception {
        Element annotation = directChildren(original, "annotation").stream().findFirst().orElse(null);
        for (Node child : directChildNodes(original)) {
            if (child != annotation) {
                original.removeChild(child);
            }
        }
        for (Element child : directChildren(generated, null)) {
            if (!"annotation".equals(child.getLocalName())) {
                original.appendChild(importElement(document, child, generatedRoot));
            }
        }
    }

    private static void mergeComplexType(Document document, Element original, Element generated, Element generatedRoot)
            throws Exception {
        Element generatedParticle = firstParticle(generated);
        if (generatedParticle != null) {
            Element originalParticle = firstParticle(original);
            if (originalParticle == null) {
                original.appendChild(importElement(document, generatedParticle, generatedRoot));
            } else {
                mergeParticle(document, originalParticle, generatedParticle, generatedRoot);
            }
        }
        for (Element generatedAttribute : descendants(generated, "attribute")) {
            String name = generatedAttribute.getAttribute("name");
            Element existing = descendants(original, "attribute").stream()
                    .filter(candidate -> candidate.getAttribute("name").equals(name))
                    .findFirst().orElse(null);
            if (existing == null) {
                original.appendChild(importElement(document, generatedAttribute, generatedRoot));
            } else {
                copyMappingAttributes(existing, generatedAttribute);
            }
        }
    }

    private static void mergeDeclaration(Document document, Element original, Element generated, Element generatedRoot)
            throws Exception {
        copyMappingAttributes(original, generated);
        for (Element child : directChildren(generated, null)) {
            if (!"annotation".equals(child.getLocalName())
                    && directChildren(original, child.getLocalName()).isEmpty()) {
                original.appendChild(importElement(document, child, generatedRoot));
            }
        }
    }

    private static void mergeParticle(Document document, Element original, Element generated, Element generatedRoot)
            throws Exception {
        List<Element> generatedElements = directChildren(generated, "element");
        Element nextElement = null;
        for (int index = generatedElements.size() - 1; index >= 0; index--) {
            Element generatedElement = generatedElements.get(index);
            String name = generatedElement.getAttribute("name");
            Element mapped = directChildren(original, "element").stream()
                    .filter(candidate -> candidate.getAttribute("name").equals(name))
                    .findFirst().orElse(null);
            if (mapped == null) {
                mapped = importElement(document, generatedElement, generatedRoot);
            } else {
                copyMappingAttributes(mapped, generatedElement);
            }
            if (mapped != nextElement) {
                original.insertBefore(mapped, nextElement);
            }
            nextElement = mapped;
        }
        for (Element generatedGroup : directChildren(generated, null)) {
            if (List.of("sequence", "choice", "all").contains(generatedGroup.getLocalName())) {
                Element existingGroup = directChildren(original, generatedGroup.getLocalName()).stream()
                        .findFirst().orElse(null);
                if (existingGroup == null) {
                    original.appendChild(importElement(document, generatedGroup, generatedRoot));
                } else {
                    mergeParticle(document, existingGroup, generatedGroup, generatedRoot);
                }
            }
        }
    }

    private static Element firstParticle(Element complexType) {
        for (Element descendant : descendants(complexType, null)) {
            if (List.of("sequence", "choice", "all").contains(descendant.getLocalName())) {
                return descendant;
            }
        }
        return null;
    }

    private static void copyMappingAttributes(Element original, Element generated) throws Exception {
        NamedNodeMap attributes = generated.getAttributes();
        boolean preserveRestrictedType = preservesNamedRestriction(original, generated);
        List<String> staleAttributes = new ArrayList<>();
        NamedNodeMap originalAttributes = original.getAttributes();
        for (int index = 0; index < originalAttributes.getLength(); index++) {
            Node attribute = originalAttributes.item(index);
            String localName = attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName();
            if (MAPPING_ATTRIBUTES.contains(localName) && !generated.hasAttribute(localName)) {
                staleAttributes.add(attribute.getNodeName());
            }
        }
        for (String attribute : staleAttributes) {
            original.removeAttribute(attribute);
        }
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                continue;
            }
            String localName = attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName();
                if (!List.of("name", "ref").contains(localName)
                    && !(preserveRestrictedType && "type".equals(localName))) {
                original.setAttribute(attribute.getNodeName(), attribute.getNodeValue());
            }
        }
    }

    private static boolean preservesNamedRestriction(Element original, Element generated) throws Exception {
        String originalType = original.getAttribute("type");
        String generatedType = generated.getAttribute("type");
        if (originalType.isBlank() || generatedType.isBlank()) {
            return false;
        }

        QName originalName = resolveQName(original, originalType);
        if (XSD.equals(originalName.getNamespaceURI())) {
            return false;
        }
        String documentUri = original.getOwnerDocument().getDocumentURI();
        if (documentUri == null) {
            return false;
        }
        QName restrictionBase = findSimpleTypeBase(Path.of(URI.create(documentUri)), originalName, new HashSet<>());
        return restrictionBase != null && restrictionBase.equals(resolveQName(generated, generatedType));
    }

    private static QName findSimpleTypeBase(Path schemaPath, QName typeName, Set<Path> visited) throws Exception {
        Path normalizedPath = schemaPath.toAbsolutePath().normalize();
        if (!visited.add(normalizedPath)) {
            return null;
        }

        Element schema = parse(normalizedPath).getDocumentElement();
        String schemaNamespace = schema.getAttribute("targetNamespace");
        if (schemaNamespace.equals(typeName.getNamespaceURI())) {
            Element simpleType = directChildren(schema, "simpleType").stream()
                    .filter(candidate -> candidate.getAttribute("name").equals(typeName.getLocalPart()))
                    .findFirst().orElse(null);
            if (simpleType != null) {
                Element restriction = directChildren(simpleType, "restriction").stream().findFirst().orElse(null);
                if (restriction != null && restriction.hasAttribute("base")) {
                    return resolveQName(restriction, restriction.getAttribute("base"));
                }
            }
        }

        for (Element dependency : directChildren(schema, null)) {
            String kind = dependency.getLocalName();
            if (!List.of("import", "include").contains(kind)) {
                continue;
            }
            if ("import".equals(kind)
                    && !dependency.getAttribute("namespace").equals(typeName.getNamespaceURI())) {
                continue;
            }
            if ("include".equals(kind) && !schemaNamespace.equals(typeName.getNamespaceURI())) {
                continue;
            }
            String location = dependency.getAttribute("schemaLocation");
            if (location.isBlank()) {
                continue;
            }
            Path dependencyPath = normalizedPath.getParent().resolve(location).normalize();
            if (Files.isRegularFile(dependencyPath)) {
                QName base = findSimpleTypeBase(dependencyPath, typeName, visited);
                if (base != null) {
                    return base;
                }
            }
        }
        return null;
    }

    private static QName resolveQName(Element context, String lexicalName) {
        int separator = lexicalName.indexOf(':');
        if (separator < 0) {
            String namespace = context.lookupNamespaceURI(null);
            return new QName(namespace == null ? "" : namespace, lexicalName);
        }
        String prefix = lexicalName.substring(0, separator);
        String namespace = context.lookupNamespaceURI(prefix);
        return new QName(namespace == null ? "" : namespace, lexicalName.substring(separator + 1));
    }

    private static Element importElement(Document target, Element source, Element generatedRoot) {
        Element imported = (Element) target.importNode(source, true);
        copyNamespaceDeclarations(imported, generatedRoot);
        return imported;
    }

    private static void copyNamespaceDeclarations(Element target, Element source) {
        NamedNodeMap attributes = source.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                String prefix = attribute.getLocalName();
                if (!target.hasAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, prefix)) {
                    target.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getNodeName(),
                            attribute.getNodeValue());
                }
            }
        }
    }

    private static List<Element> directChildren(Element parent, String localName) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && XSD.equals(element.getNamespaceURI())
                    && (localName == null || localName.equals(element.getLocalName()))) {
                children.add(element);
            }
        }
        return children;
    }

    private static List<Node> directChildNodes(Element parent) {
        List<Node> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            children.add(child);
        }
        return children;
    }

    private static void removeFormattingWhitespace(Node parent, boolean preserveWhitespace) {
        boolean preserveChildren = preserveWhitespace;
        if (parent instanceof Element element && XSD.equals(element.getNamespaceURI())) {
            if ("appinfo".equals(element.getLocalName())) {
                preserveChildren = true;
            } else if ("annotation".equals(element.getLocalName())) {
                preserveChildren = preserveWhitespace;
            } else if ("documentation".equals(element.getLocalName()) && !preserveWhitespace) {
                preserveChildren = hasElementChild(element);
            }
        }
        for (Node child = parent.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.TEXT_NODE && child.getNodeValue().isBlank() && !preserveChildren) {
                parent.removeChild(child);
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                removeFormattingWhitespace(child, preserveChildren);
            }
            child = next;
        }
    }

    private static boolean hasElementChild(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    private static List<Element> descendants(Element parent, String localName) {
        List<Element> matches = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagNameNS(XSD, localName == null ? "*" : localName);
        for (int index = 0; index < nodes.getLength(); index++) {
            matches.add((Element) nodes.item(index));
        }
        return matches;
    }

    private static Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    @FunctionalInterface
    private interface DeclarationMerger {
        void merge(Document document, Element original, Element generated, Element generatedRoot) throws Exception;
    }
}