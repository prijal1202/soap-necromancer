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
5. WHEN the document has more than one `wsdl:types` element THEN the parser SHALL read the schemas of all of them, and criterion 2 SHALL hold for every one.
6. WHEN any part of the input cannot be processed (invalid schema content such as `minOccurs="x"`, undeclared prefixes, conflicting schema locations, missing `name` attributes) THEN the parser SHALL return a fatal error or a warning, and SHALL NOT fail in any other way.

### Requirement 2: Service structure

**User Story:** As a developer, I want each SOAP operation mapped to one model operation, so that
every generator exposes exactly the legacy service's capabilities.

#### Acceptance Criteria

1. WHEN the root element is not `{http://schemas.xmlsoap.org/wsdl/}definitions` THEN the parser SHALL return a fatal error (this covers WSDL 2.0).
2. WHEN a port uses a SOAP 1.1 or SOAP 1.2 binding THEN the parser SHALL use it and record the `soap:address` or `soap12:address` location as the endpoint URL.
3. The parser SHALL record the chosen binding's SOAP version (`SOAP_11` or `SOAP_12`) in the model. SOAP 1.2 bindings SHALL follow every criterion below exactly like SOAP 1.1 ones, with the `soap12:` elements in place of the `soap:` ones.
4. WHEN an operation is `rpc` style THEN the parser SHALL skip it and emit `RPC_STYLE`.
5. WHEN an operation body is `use="encoded"` THEN the parser SHALL skip it and emit `ENCODED_USE`.
6. WHEN an input or output message does not have exactly one body part bound to an element THEN the parser SHALL skip the operation and emit `UNSUPPORTED_MESSAGE`. Body parts are those listed by `soap:body parts=`; without `parts=`, every part not bound by a `soap:header` of the same message, or every part when that would leave none (WSDL 1.1 section 3.5).
7. WHEN an operation has no output (one-way) THEN the parser SHALL skip it and emit `ONE_WAY_OPERATION`.
8. WHEN an operation declares `soap:header` (or `soap12:header`) THEN the parser SHALL keep the operation, ignore the header and emit `SOAP_HEADER`.
9. WHEN the document contains WS-Policy or WS-Security elements THEN the parser SHALL emit `WS_SECURITY` and keep the operations.
10. WHEN more than one SOAP port exists THEN the parser SHALL use the first SOAP 1.1 port in document order, or, without one, the first SOAP 1.2 port, and emit `MULTIPLE_PORTS` naming the chosen and the skipped ports. (Services such as .NET ASMX publish both versions for the same operations; SOAP 1.1 is the most widely interoperable.)
11. WHEN no usable operation remains THEN the parser SHALL return a fatal error that lists the warnings.
12. The parser SHALL record each operation's name, soapAction, documentation, input element and output element, and the service's SOAP version.
13. WHEN a port's binding is undefined or is not a SOAP binding (for example HTTP or MIME) THEN the parser SHALL skip the port and emit `UNSUPPORTED_BINDING`.
14. WHEN the chosen port has no address THEN the parser SHALL emit `NO_ADDRESS` (the endpoint must then be configured).
15. WHEN no `wsdl:service` has a usable port THEN the parser SHALL use the first SOAP binding in document order (SOAP 1.1 first, as in criterion 10), emit `NO_ADDRESS`, and emit `MULTIPLE_PORTS` if other SOAP bindings are skipped.
16. WHEN a binding has two operations with the same name (overloads) THEN the parser SHALL keep the first and emit `DUPLICATE_OPERATION`.
17. WHEN an operation name does not match `[A-Za-z_][A-Za-z0-9_.-]*` or is longer than 128 characters THEN the parser SHALL skip the operation and emit `UNSUPPORTED_NAME`. Operation names become URL paths, MCP tool names and file names.

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
12. WHEN an element name is not an XML NCName THEN the parser SHALL drop the field and emit `XSD_INVALID_NAME` (names are written into XML by the converter).
13. WHEN `minOccurs` is larger than `maxOccurs` or larger than 1000 THEN the parser SHALL emit `XSD_OCCURS` and clamp it to the smaller of the two bounds.

### Requirement 5: Resource limits

**User Story:** As the operator of a public generator on 512 MB, I want hostile or huge WSDLs to
fail fast with a clear reason, so that one upload cannot crash the service or block other users.

#### Acceptance Criteria

1. WHEN the XML nests deeper than 256 elements THEN the parser SHALL return a fatal error.
2. WHEN the WSDL has more than 200 binding operations THEN the parser SHALL return a fatal error with warning `LIMIT_OPERATIONS`.
3. WHEN more than 1000 distinct types are reachable from the operations THEN the parser SHALL stop and return a fatal error with `LIMIT_TYPES`.
4. WHEN a chain of nested or referenced types is deeper than 64 THEN the parser SHALL stop and return a fatal error with `LIMIT_DEPTH`. Limits 3 and 4 SHALL be checked while walking, so the walk never overflows the stack.
5. WHEN complex types form a cycle through required fields (minOccurs >= 1), so that no finite value exists, THEN the parser SHALL return a fatal error with `UNSATISFIABLE_RECURSION` naming the types.
6. Recursion through optional or zero-minimum repeated fields (for example a tree) SHALL remain supported.

### Requirement 4: Output

#### Acceptance Criteria

1. The parser SHALL return the model and the warnings in document order.
2. The model SHALL serialize to deterministic JSON (`model.json`).
3. WHEN `bank.wsdl` is parsed THEN the result SHALL equal the golden `model.json` and have no warnings.
