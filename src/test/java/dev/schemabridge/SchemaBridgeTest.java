package dev.schemabridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

class SchemaBridgeTest {
    @Test
    void allMockSchemasAreValid() throws Exception {
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try (var schemas = Files.list(Path.of("examples"))) {
            var schemaFiles = schemas.filter(path -> path.toString().endsWith(".xsd")).toList();
            assertEquals(11, schemaFiles.size());
            for (Path schema : schemaFiles) {
                schemaFactory.newSchema(schema.toFile());
            }
        }
    }

    @Test
    void mergePreservesDocumentationAndAddsJavaProperties() throws Exception {
        Path directory = Files.createTempDirectory("schema-bridge-test-");
        Path original = directory.resolve("original.xsd");
        Path generated = directory.resolve("generated.xsd");
        Path output = directory.resolve("result.xsd");
        Files.writeString(original, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:test" targetNamespace="urn:test" elementFormDefault="qualified">
                  <xs:simpleType name="CodeType"><xs:restriction base="xs:string"><xs:pattern value="[A-Z]+"/></xs:restriction></xs:simpleType>
                  <xs:complexType name="RecordType">
                    <xs:annotation><xs:documentation>Keep this contract note.</xs:documentation></xs:annotation>
                    <xs:sequence><xs:element name="name" type="t:CodeType"/></xs:sequence>
                    <xs:attribute name="status" type="xs:string" use="required" default="legacy"/>
                  </xs:complexType>
                  <xs:element name="record" type="t:RecordType"/>
                </xs:schema>
                """);
        Files.writeString(generated, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:test" targetNamespace="urn:test" elementFormDefault="qualified">
                  <xs:complexType name="RecordType">
                    <xs:sequence><xs:element name="note" type="xs:string" minOccurs="0"/><xs:element name="name" type="xs:string"/></xs:sequence>
                    <xs:attribute name="status" type="xs:string"/>
                    <xs:attribute name="revision" type="xs:int" use="optional"/>
                  </xs:complexType>
                </xs:schema>
                """);
        SchemaMerger.merge(original, generated, output);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document result = factory.newDocumentBuilder().parse(output.toFile());
        assertTrue(result.getDocumentElement().getTextContent().contains("Keep this contract note."));
        assertEquals(2, result.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "attribute").getLength());
        assertEquals(3, result.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "element").getLength());
        var sequence = result.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "sequence").item(0);
        Node firstElement = sequence.getFirstChild();
        while (firstElement != null && firstElement.getNodeType() != Node.ELEMENT_NODE) {
          firstElement = firstElement.getNextSibling();
        }
        assertEquals("note", firstElement.getAttributes().getNamedItem("name").getNodeValue());
        Node secondElement = firstElement.getNextSibling();
        while (secondElement != null && secondElement.getNodeType() != Node.ELEMENT_NODE) {
          secondElement = secondElement.getNextSibling();
        }
        assertEquals("name", secondElement.getAttributes().getNamedItem("name").getNodeValue());
        var namedElement = result.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "element").item(1);
        assertEquals("t:CodeType", namedElement.getAttributes().getNamedItem("type").getNodeValue());
        var statusAttribute = result.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "attribute").item(0);
        assertFalse(((org.w3c.dom.Element) statusAttribute).hasAttribute("use"));
        assertEquals("legacy", statusAttribute.getAttributes().getNamedItem("default").getNodeValue());
        assertFalse(Files.readAllLines(output).stream().anyMatch(String::isBlank));
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(output.toFile());
    }
}