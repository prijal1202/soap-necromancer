# Tech

## Stack

- Java 21, Spring Boot 3, Maven multi-module.
- UI: a single static HTML page served by Spring Boot. React/Vite only if time allows.
- Deployment: one Docker container on Render, one JVM (generator web app + `mock-bank`).

## Invariants

- REST proxy uses a model-driven JSON<->XML converter in the runtime module; no JAXB/XJC.
- `runtime` stays tiny and dependency-light (JDK DOM/StAX only). It ships inside every
  generated proxy.
- Generation is pure and in-memory: WSDL in, file map out, zip returned from
  `POST /api/generate`. The server holds no state and writes nothing to disk.
- Parse uploaded XML with DTDs and external entities disabled (XXE hardening).
- The generated MCP server runs over stdio and only forwards tool calls to the generated
  proxy over REST. It never talks SOAP.
- Pin dependency versions. No open ranges.

## Testing

- jqwik property tests verify JSON<->XML round trips against the shipped converter.
- Fast jqwik tests are tagged `fast` and use a capped try count. The Kiro hook runs only these.
- The generated-project build test is slow and is never run by hooks.

## Decided libraries (see `docs/decisions.md`)

- Parsing: `wsdl4j:wsdl4j:1.6.3` + `org.apache.ws.xmlschema:xmlschema-core:2.3.3`. No CXF.
  Uploads are parsed with a hardened DOM first; WSDL4J never fetches imports.
- Generated MCP server: `io.modelcontextprotocol.sdk:mcp:2.1.0`, classic sync API, stdio.
  Logs go to stderr only (stdout is the protocol channel).
- OpenAPI: emitted directly from the canonical model; `swagger-parser` 2.1.48 in test scope only.
- Templates: JMustache 1.16, under `generators/src/main/resources/templates/`.
- Render: `server.port=${PORT:8080}`, mock-bank at `/mock-bank/ws` in the same JVM,
  `-XX:MaxRAMPercentage=70 -XX:+UseSerialGC`.
