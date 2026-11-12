# Design: WSDL Parser and Canonical Model

## Placement

- Model records: `runtime` (`dev.soapnecromancer.runtime.model`). The `runtime` module is JDK-only
  and its sources are copied into every generated proxy, so the generated proxy and the generator
  share one definition of the model.
- Parser: `core` (`dev.soapnecromancer.core`). Depends on `runtime`, WSDL4J 1.6.3, XmlSchema 2.3.3,
  Jackson (for `model.json` only).

## Canonical model

```
ServiceModel(name, targetNamespace, endpointUrl, operations[], types{id -> TypeModel})
OperationModel(name, soapAction, documentation, input: ElementRef, output: ElementRef)
ElementRef(namespace, localName, typeId)            top-level element of a message part
TypeModel(id, kind: COMPLEX|SIMPLE, base: BuiltIn?, enumValues[], fields[])
FieldModel(name, namespace, typeId, minOccurs, maxOccurs)   maxOccurs -1 = unbounded
BuiltIn: STRING, INT, LONG, DECIMAL, BOOLEAN, DATE, DATE_TIME
```

A single `TypeModel` record (with `kind`) instead of a sealed hierarchy keeps `runtime` free of
JSON annotations and keeps Mustache templates simple. Built-ins appear in `types` as
`xs:string`, `xs:int` and so on (SIMPLE, no enum values). They are added only when used.

Type ids: named type -> its local name. Anonymous type of a top-level element -> the element
name. Anonymous type of a local element -> `<ParentTypeId><FieldName>`. On collision, a numeric
suffix is appended. Resolution is memoized by schema object identity, and the id is reserved
before recursing, so recursive types terminate.

## Pipeline

1. `SecureXml.parse(bytes)`: namespace-aware DOM, DOCTYPE disallowed, no external access.
2. `WSDLReader.readWSDL(null, document)` with `importDocuments=false`. `wsdl:import` -> `EXTERNAL_IMPORT`.
3. DOM scan for WS-Policy / WS-Security namespaces -> `WS_SECURITY`.
4. XmlSchema: every inline `xs:schema` element is read into one `XmlSchemaCollection` with a
   resolver that never fetches. It returns an empty schema and records `EXTERNAL_IMPORT`.
5. Pick ports: first service order, SOAP 1.1 only. Without services, fall back to SOAP 1.1
   bindings with `endpointUrl = null` (`NO_ADDRESS`).
6. For each binding operation: style, use, parts, header and output checks, then resolve
   input and output elements to types.
7. Fatal (`WsdlParseException`) when zero operations remain.

## Warnings

`Warning(code, message, location)`. Location is a readable path, for example
`binding[BankSoapBinding]/operation[GetBalance]` or `type[Account]/choice`.

## Error handling

`WsdlParseException` carries a message and the warnings collected so far. The web layer maps it
to HTTP 422 with a JSON body.

## Testing

- Golden test: `bank.wsdl` -> `bank.model.json` (JSON tree equality), zero warnings.
- One negative fixture per warning code group: soap12, rpc/encoded, choice/attribute/any,
  derivation, ws-security, external import, unknown built-in, one-way, multi-part.
- Fatal: not XML, DOCTYPE, WSDL 2.0, no SOAP 1.1 binding.
