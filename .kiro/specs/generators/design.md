# Design: Generators

## Module layout (`generators`, package `dev.soapnecromancer.generators`)

```
Generator            ParseResult -> GeneratedFiles (ordered map path -> bytes); single entry point
GeneratedFiles       file map + deterministic zip writer
OpenApiGenerator     model -> openapi.yaml (hand-written YAML emitter)
JsonSchemas          model -> JSON Schema maps; shared by OpenApiGenerator and McpServerGenerator
ProxyGenerator       templates + copied runtime sources -> proxy/**
TestGenerator        templates + copied test helpers -> proxy/src/test/**
McpServerGenerator   templates -> mcp-server/**
Names                service/package/artifact/tool/component name sanitizing
Templates            JMustache loader for src/main/resources/templates/**
```

Dependencies: `core`, `runtime`, JMustache 1.16, Jackson (already in `core`, used for
`model.json` and `generator-report.json`). Test scope: swagger-parser 2.1.48, jqwik, AssertJ.

## Runtime sources travel as classpath resources

The generator must copy `runtime`'s `.java` files into `proxy/` without reading the repo at run
time (on Render there is no source tree). `generators/pom.xml` copies
`runtime/src/main/java/**` and three test helpers (`ModelArbitraries`, `XmlRenderer`,
`RoundTripAssertions`) into `target/classes/runtime-src/` during `generate-resources`
(maven-resources-plugin). A generated index file lists them. `ProxyGenerator` reads that index
from the classpath.

So the model records reach the generated proxy only this way. The generator never emits model
classes of its own (Requirement 2.2). At startup the generated proxy reads `model.json` with
Jackson straight into the records (Jackson supports records natively).

## Generated proxy shape

```
proxy/pom.xml                                       spring-boot-starter-web, test: rest-assured, jqwik
proxy/src/main/java/dev/soapnecromancer/runtime/**  copied runtime sources (incl. SoapClient, model)
proxy/src/main/java/<pkg>/ProxyApplication.java
proxy/src/main/java/<pkg>/ModelConfig.java          loads model.json -> ServiceModel, JsonXmlConverter, SoapClient
proxy/src/main/java/<pkg>/<Op>Controller.java       one per operation: POST /<Op>
proxy/src/main/java/<pkg>/ProblemHandler.java       exception -> Problem mapping (Req 2.6)
proxy/src/main/resources/model.json
proxy/src/main/resources/application.properties     soap.endpoint=${SOAP_ENDPOINT:<address>}, Jackson BigDecimal flags
proxy/src/test/java/dev/soapnecromancer/runtime/**  copied ModelArbitraries, XmlRenderer, RoundTripAssertions
proxy/src/test/java/<pkg>/StubSoapServer.java
proxy/src/test/java/<pkg>/<Op>ContractTest.java     REST Assured, Req 3.1-3.4, 6.3
proxy/src/test/java/<pkg>/RoundTripProperties.java  jqwik over model.json, Req 3.5
```

One controller per operation keeps the generated code readable for the demo. Each controller is a
thin template: it parses the body as a `JsonNode`-free `Object` (Jackson `Object.class`, BigDecimal
flags on) and calls a shared `SoapOperationInvoker`.

`SoapClient` (new, in `runtime`): builds the envelope with the converter, posts with JDK
`HttpClient`, parses the response with `RuntimeXml`, and returns the payload element or throws
`SoapFaultException(faultcode, faultstring)` / `SoapTransportException(timeout|connect)`.

## Generated MCP server shape

```
mcp-server/pom.xml                                   mcp 2.1.0, slf4j-simple to stderr, shade plugin
mcp-server/src/main/java/<pkg>/McpServerMain.java    registers tools from tools.json
mcp-server/src/main/resources/tools.json             name, description, inputSchema per operation
mcp-server/README.md                                 build + Kiro mcp.json snippet
```

Tool schemas are data (`tools.json`) produced by `JsonSchemas`, not Java code, so one small
template serves every WSDL. The forwarder uses JDK `HttpClient`. Decimal handling follows
Requirement 6.2 and 6.4. Whether the SDK's `McpJsonMapper` can be built from a Jackson mapper
with `USE_BIG_DECIMAL_FOR_FLOATS` is checked at the start of Task 9.

## Determinism

Ordered maps everywhere, model order for operations and fields, sorted zip entries,
`ZipEntry.setTimeLocal(2026-01-01T00:00)`, LF line endings in every template output.

## Testing

| Layer | What | Where | Hook |
| --- | --- | --- | --- |
| Golden | `openapi.yaml`, `tools.json`, file list for bank.wsdl | `generators` surefire, tag `fast` | yes |
| Validity | swagger-parser on `openapi.yaml`; JSON Schema meta-schema check on tool schemas | `generators` surefire, tag `fast` | yes |
| Determinism | same input -> identical zip bytes | `generators` surefire, tag `fast` | yes |
| Generated tests | contract + jqwik in the generated proxy | run inside the IT | no |
| End to end | `GeneratedProjectIT`: mvn package both projects, mock-bank, generated proxy, generated MCP server, GetBalance + Transfer | `generators` failsafe (`verify`) | no |

The IT needs `mvn` on PATH and a populated local repository. It is skippable with `-DskipITs`.
