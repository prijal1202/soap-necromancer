# Requirements: WSDL Parser and Canonical Model

## Introduction

The generator's `core` module turns an uploaded WSDL 1.1 document into the canonical model that
every generator (OpenAPI, generated proxy, generated tests, generated MCP server) and the `runtime`
converter consume. Unsupported features never fail silently: they become warnings with a location.

## Glossary

- **Canonical model**: `ServiceModel` and its operations and types (`dev.soapnecromancer.runtime.model`).
- **Warning**: `{code, message, location}` reported to the UI via `generator-report.json`.
- **Fatal error**: the input cannot produce any operation; the generator returns an error, no zip.

## Requirements

### Requirement 1: Secure input handling

**User Story:** As an operator of the hosted generator, I want uploads parsed safely, so that a
malicious WSDL cannot read files or reach internal hosts.

#### Acceptance Criteria

1. WHEN the input contains a DOCTYPE declaration THEN the parser SHALL reject it with a fatal error.
2. The parser SHALL NOT fetch any `wsdl:import`, `xs:import` or `xs:include` location over the network or file system.
3. WHEN an import or include references an external location THEN the parser SHALL emit warning `EXTERNAL_IMPORT` with the location attribute.
4. WHEN the input is not well-formed XML THEN the parser SHALL return a fatal error describing the problem.

### Requirement 2: Service structure

**User Story:** As a developer, I want each SOAP operation mapped to one model operation, so that
every generator exposes exactly the legacy service's capabilities.

#### Acceptance Criteria

1. WHEN the root element is not `{http://schemas.xmlsoap.org/wsdl/}definitions` THEN the parser SHALL return a fatal error (this covers WSDL 2.0).
2. WHEN a port uses a SOAP 1.1 binding THEN the parser SHALL use it and record the `soap:address` location as the endpoint URL.
3. WHEN a binding is SOAP 1.2 THEN the parser SHALL skip it and emit `SOAP12_BINDING`.
4. WHEN an operation is `rpc` style THEN the parser SHALL skip it and emit `RPC_STYLE`.
5. WHEN an operation body is `use="encoded"` THEN the parser SHALL skip it and emit `ENCODED_USE`.
6. WHEN an input or output message does not have exactly one part bound to an element THEN the parser SHALL skip the operation and emit `UNSUPPORTED_MESSAGE`.
7. WHEN an operation has no output (one-way) THEN the parser SHALL skip it and emit `ONE_WAY_OPERATION`.
8. WHEN an operation declares `soap:header` THEN the parser SHALL keep the operation, ignore the header and emit `SOAP_HEADER`.
9. WHEN the document contains WS-Policy or WS-Security elements THEN the parser SHALL emit `WS_SECURITY` and keep the operations.
10. WHEN more than one SOAP 1.1 port exists THEN the parser SHALL use the first and emit `MULTIPLE_PORTS`.
11. WHEN no usable operation remains THEN the parser SHALL return a fatal error that lists the warnings.
12. The parser SHALL record each operation's name, soapAction, documentation, input element and output element.

### Requirement 3: XSD core subset

**User Story:** As a developer, I want the supported XSD subset mapped precisely, so that JSON and
XML convert without data loss.

#### Acceptance Criteria

1. The parser SHALL map `xs:string`, `xs:int`, `xs:long`, `xs:decimal`, `xs:boolean`, `xs:date` and `xs:dateTime` to built-in types.
2. WHEN another built-in type is used THEN the parser SHALL map it to string and emit `UNSUPPORTED_BUILTIN`.
3. The parser SHALL map `xs:sequence` children to fields in document order, with `minOccurs` and `maxOccurs` (unbounded = -1).
4. The parser SHALL map a string restriction with `xs:enumeration` facets to an enum type that keeps the values in order.
5. The parser SHALL resolve named types, anonymous types and `ref=` elements across all inline schemas of the WSDL.
6. The parser SHALL record each field's namespace from its form (`elementFormDefault` or `form`), using an empty namespace when unqualified.
7. WHEN `xs:choice`, `xs:all`, `xs:any`, `xs:group`, attributes, `complexContent`, `simpleContent`, `nillable`, list or union types are used THEN the parser SHALL emit a specific warning (`XSD_CHOICE`, `XSD_ALL`, `XSD_ANY`, `XSD_GROUP`, `XSD_ATTRIBUTE`, `XSD_DERIVATION`, `XSD_NILLABLE`, `XSD_LIST_UNION`), drop the unsupported construct, and keep the rest of the type.
8. WHEN a referenced type cannot be resolved THEN the parser SHALL map it to string and emit `UNRESOLVED_TYPE`.
9. WHEN schemas declare more than one target namespace THEN the parser SHALL process them and emit `MULTIPLE_NAMESPACES`.
10. Type ids SHALL be unique and stable for the same input.
11. WHEN a complex type has two fields with the same local name THEN the parser SHALL keep the first, drop the rest and emit `XSD_DUPLICATE_FIELD` (field names are JSON property names).

### Requirement 4: Output

#### Acceptance Criteria

1. The parser SHALL return the model and the warnings in document order.
2. The model SHALL serialize to deterministic JSON (`model.json`).
3. WHEN `bank.wsdl` is parsed THEN the result SHALL equal the golden `model.json` and have no warnings.
