# Design: Model-driven JSON <-> XML Converter

## API (`dev.soapnecromancer.runtime`)

```java
JsonXmlConverter(ServiceModel model)
String toXml(ElementRef root, Object json)      // payload element only, no envelope
Object toJson(ElementRef root, String xml)
Object toJson(ElementRef root, org.w3c.dom.Element element)   // used by the SOAP client
ConversionException(String path, String message)
```

The root type is normally complex (document/literal wrapped). A simple root is supported: its
JSON value is the scalar.

## Writing

A `StringBuilder` writer, not `XMLStreamWriter`, because StAX does not escape `\r`, and the
parser would normalize it to `\n` (breaks Requirement 1.12). Namespaces reachable from the root
type are collected once (memoized per model + root) and declared on the root as `ns1..nN` in
discovery order. Unqualified elements are written without a prefix; no default namespace is ever
declared, so they stay in no namespace.

Text escaping: `&` `<` `>` `\r` -> entities. Characters are checked by code point against the
XML 1.0 `Char` production, so unpaired surrogates and control characters fail.

## Reading

Hardened `DocumentBuilder` (same settings as `core.SecureXml`, duplicated because `runtime`
cannot depend on `core`). Children are grouped by `{namespace}localName` and then mapped in
field order into a `LinkedHashMap`.

## Value mapping

| Built-in | JSON in (accepted) | XML | JSON out |
| --- | --- | --- | --- |
| STRING | String | as-is | String |
| INT / LONG | integral Number or numeric String, range-checked | decimal digits | Integer / Long |
| DECIMAL | Number or numeric String | `toPlainString()` | BigDecimal |
| BOOLEAN | Boolean or "true"/"false" | true/false | Boolean (`1`/`0` accepted on read) |
| DATE / DATE_TIME | ISO String (validated) | as-is | String |
| enum | declared value | as-is | String |

`Double` decimals go through `BigDecimal.valueOf`. The generated proxy configures Jackson with
`USE_BIG_DECIMAL_FOR_FLOATS` so decimals never pass through binary floating point.

## Testing

- `runtime` tests: `ModelArbitraries` generates random models (nested complex types up to depth
  3, namespaces from {urn:a, urn:b, unqualified}, occurs 0..1 / 1..1 / 0..2 / 0..*, all built-ins,
  enums) and canonical values for them. `XmlRenderer` (test-only) renders values with random
  prefixes, default namespaces, whitespace and CDATA for Requirement 3.2.
- `core` tests: bank.wsdl round trips, reusing the arbitraries via the runtime test-jar.
- Tries: `jqwik.tries.default` comes from the Maven property `jqwik.tries` (default 1000). The Kiro
  hook runs `-Dgroups=fast -Djqwik.tries=100`.
- Dependency budget: maven-enforcer `bannedDependencies` on compile and runtime scope in `runtime`.
