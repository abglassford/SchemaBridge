# SchemaBridge

SchemaBridge generates JAXB-annotated Java classes from XML Schema and syncs edited Java mappings back into a copy of the original schema. The reverse operation retains existing schema documentation and annotations, updates mappings represented by the Java source, and adds newly mapped fields and types.

## Requirements

- JDK 17 or newer
- Maven 3.8 or newer

## Build and test

```powershell
mvn test
```

## Generate Java

```powershell
mvn compile exec:java "-Dexec.args=to-java examples/customers.xsd target/generated-sources/customers"
```

An optional final argument sets the generated Java package:

```powershell
mvn compile exec:java "-Dexec.args=to-java examples/catalog.xsd target/generated-sources/catalog com.example.catalog"
```

## Sync edited Java back to XSD

Edit the generated Java mapping, including JAXB annotations and `@XmlType(propOrder)` when applicable, then pass the original schema and the directory containing the Java source files:

```powershell
mvn compile exec:java "-Dexec.args=sync-schema examples/customers.xsd target/generated-sources/customers target/roundtrip/customers.xsd"
```

SchemaBridge runs JAXB schemagen on the Java sources, then merges the generated declarations into a copy of the original schema. Existing `xs:documentation` and `xs:annotation` nodes are retained for matching declarations. New mapped fields, attributes, types, and elements are brought across. The original input file is never modified.

The reverse pass follows JAXB mapping rules: Java fields must be visible to JAXB through annotations or access configuration, and generated classes with `@XmlType(propOrder)` must include newly added properties there. Schema constructs with no Java representation remain from the original XSD.

## Example schemas

The `examples/` directory contains ten domain schemas plus `common-types.xsd`. Billing and logistics import shared currency, country, and postal-code types from the common namespace using `xs:import`; the other schemas demonstrate restrictions, enumerations, documented simple and complex types, attributes, and annotated elements.

The reverse merger targets named JAXB complex types and their sequence elements/attributes. For substantially different schema structures, review the generated output before adopting it as a contract.