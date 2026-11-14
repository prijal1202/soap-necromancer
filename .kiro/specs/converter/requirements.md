# Requirements: Model-driven JSON <-> XML Converter

## Introduction

`runtime` converts between JSON values and SOAP body payload XML using only the canonical model.
It ships inside every generated proxy, so it uses the JDK only (no JAXB/XJC, no Jackson).
JSON values are plain Java: `Map<String,Object>`, `List<Object>`, `String`, `Number`, `Boolean`,
so the generated proxy (Jackson) and the generated MCP server (MCP SDK) can both hand it their parsed JSON.

## Glossary

- **Canonical JSON**: what `toJson` returns. Objects are `LinkedHashMap` in model field order.
  `xs:int` -> `Integer`, `xs:long` -> `Long`, `xs:decimal` -> `BigDecimal`, `xs:boolean` -> `Boolean`,
  strings, enums, `xs:date` and `xs:dateTime` -> `String` (lexical form kept). Repeated fields are
  always present as a list (possibly empty). Absent optional single fields have no key.
- **Path**: JSONPath-like location in errors, for example `$.line[2].amount`.

## Requirements

### Requirement 1: JSON to XML (requests)

**User Story:** As a REST client or AI agent, I want clear errors for bad input, so that I can fix
my request instead of getting a SOAP fault from the legacy backend.

#### Acceptance Criteria

1. The converter SHALL write the root element with the namespace and local name of the operation's `ElementRef`.
2. The converter SHALL write child elements in model field order, regardless of JSON key order.
3. The converter SHALL qualify each element with its field namespace, and leave unqualified fields in no namespace.
4. WHEN a required field is missing or null THEN the converter SHALL fail with a `ConversionException` naming the path.
5. WHEN an optional field is missing or null THEN the converter SHALL write no element for it.
6. WHEN a repeated field is present THEN its value SHALL be a JSON array whose size is within minOccurs..maxOccurs; otherwise the converter SHALL fail.
7. WHEN a JSON object has a key that is not a field of its type THEN the converter SHALL fail naming the key.
8. The converter SHALL accept for numbers either JSON numbers or numeric strings, and for booleans either JSON booleans or `"true"`/`"false"`, and SHALL reject values out of the type's range. It SHALL reject, before any arithmetic, numbers with more than 1000 digits before the decimal point or a scale outside -1000..1000: a 13-byte `1e999999999` would otherwise cost about 1 GB to write out.
9. WHEN an enum value is not one of the declared values THEN the converter SHALL fail and list the allowed values.
10. WHEN a date or dateTime string is not ISO-8601 THEN the converter SHALL fail. A dateTime SHALL be a local or offset date-time; a bracketed zone id (`[Europe/Paris]`, which ISO-8601 parsers accept but xs:dateTime does not) SHALL fail.
11. The converter SHALL write decimals in plain notation (no exponent).
12. The converter SHALL escape `&`, `<`, `>` and carriage return in text so the parsed XML has exactly the original string.
13. WHEN a string contains a character not allowed in XML 1.0 THEN the converter SHALL fail rather than drop or alter it.
14. WHEN a value nests deeper than 256 levels THEN the converter SHALL fail with a `ConversionException` (recursive types would otherwise recurse without bound).

### Requirement 2: XML to JSON (responses)

**User Story:** As a client of the generated proxy, I want legacy SOAP responses as clean JSON,
including data from slightly non-conforming backends.

#### Acceptance Criteria

1. WHEN the root element's namespace or local name does not match the expected `ElementRef` THEN the converter SHALL fail.
2. The converter SHALL match child elements by namespace and local name, in any order.
3. The converter SHALL ignore child elements that are not fields of the type.
4. WHEN a single-valued field occurs more than once THEN the converter SHALL fail rather than drop data.
5. The converter SHALL return repeated fields as lists, empty when no element occurs.
6. The converter SHALL keep string text exactly, and trim whitespace for all other built-ins.
7. The converter SHALL accept `1`/`0` as booleans and return enum values that are not declared unchanged.
8. The converter SHALL parse XML with DOCTYPE disallowed and no external entity resolution, and SHALL fail on elements nested deeper than 256 levels.
9. WHEN an element carries `xsi:nil="true"` (or `"1"`) THEN the converter SHALL treat it as absent: no key for a single field, no item for a repeated field. Backends built on .NET and Axis send nil for `nillable` elements routinely.
10. Numbers SHALL obey the bounds of Requirement 1.8; within them, exponent notation is accepted and returned as `BigDecimal`.
11. Responses are read leniently (2.3): a missing required element is not an error, so data from slightly non-conforming backends still comes through. Only data that cannot be represented fails (2.1, 2.4, 2.8, 2.10).
12. Error messages SHALL quote at most 40 characters of a value from the XML, so backend text (for example a stack trace inside an element) does not reach clients.

### Requirement 3: Round-trip guarantees (verified with jqwik)

#### Acceptance Criteria

1. For any model in the supported subset and any canonical JSON value `v` of its root type, `toJson(toXml(v))` SHALL equal `v`.
2. For any such `v` rendered as XML `x` with arbitrary prefixes, default-namespace declarations, whitespace between elements and CDATA, `toJson(x)` SHALL equal `v`, and `toJson(toXml(toJson(x)))` SHALL equal `v`.
3. For any `v`, every optional single-valued field absent from `v` SHALL produce no element in `toXml(v)`.
4. For every operation in `bank.wsdl`, properties 1 and 3 SHALL hold for both input and output types.
5. Fast property runs (tag `fast`) SHALL use a capped try count (100), and full runs SHALL use 1000.
6. For any `BigDecimal` `d` (up to 31 significant digits, scale up to 20), `toJson(toXml(d))` SHALL have the same unscaled value and scale as `d` (`0.10` stays `0.10`), and the XML text SHALL be `d.toPlainString()`.

Note: the converter takes parsed values, not JSON text. Exactness end to end also requires every
JSON layer in front of it to produce `BigDecimal` for decimals, never `double`. That is a
requirement on the generated proxy and the generated MCP server (generators spec, Requirement 6).

### Requirement 4: Dependency budget

1. `runtime` SHALL have no compile or runtime dependencies beyond the JDK, enforced by the build.
