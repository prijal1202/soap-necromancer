# Decisions (Task 2 spikes)

One decision per topic. Spike code lives in `spikes/` and is not part of the Maven reactor.

## 1. Generated MCP server SDK: Java, stdio. PASS

- Decision: the generated MCP server uses `io.modelcontextprotocol.sdk:mcp:2.1.0` (latest stable;
  2.2.0 is still a milestone) with the classic `McpServer.sync(...)` API and
  `StdioServerTransportProvider`. No Spring, no TypeScript fallback.
- Why: the convenience `mcp` artifact bundles Jackson 3 and the stdio transport with no web
  framework. The "modern" 2026-07-28 server is a preview, so we avoid it.
- Evidence: `spikes/mcp-stdio`. A shaded jar launched as `java -jar` answered `initialize`,
  `tools/list` (with the JSON Schema input) and `tools/call` over newline-delimited JSON-RPC.
- Constraint: stdout is the protocol channel. The generated MCP server must log to stderr only
  (spike uses `slf4j-nop`).

## 2. Parsing: WSDL4J 1.6.3 + Apache XmlSchema 2.3.3. PASS

- Decision: `wsdl4j:wsdl4j:1.6.3` for WSDL structure, `org.apache.ws.xmlschema:xmlschema-core:2.3.3`
  for XSD. No CXF.
- Why: both are small and only use `javax.wsdl` / `org.apache.ws.commons.schema`, which do not
  clash with Jakarta EE on Spring Boot 3. WSDL4J is unmaintained since 2017 but stable.
- XXE: the generator parses uploads with a hardened `DocumentBuilderFactory`
  (`disallow-doctype-decl`, secure processing, no external DTD/schema access) and passes the DOM to
  `WSDLReader.readWSDL(null, Document)` with `javax.wsdl.importDocuments=false`, so WSDL4J never
  fetches anything. Only imports between inline schemas in the same upload resolve; every external
  `wsdl:import`, `xs:import`, `xs:include` or `xs:redefine` becomes an `EXTERNAL_IMPORT` warning.
- Evidence: `spikes/wsdl-parse` printed binding style, operation input element QNames, and child
  elements with min/max occurs; a DOCTYPE payload was rejected.
- Correction found in Task 4 (test `req1_2_1_3_externalSchemaImportIsNotFetchedAndWarns`): WSDL4J
  still resolves `xs:import schemaLocation` inside `wsdl:types` even with `importDocuments=false`,
  so the test tried to reach `169.254.169.254` (SSRF). Fix: WSDL4J receives a copy of the DOM with
  `wsdl:types` removed; schemas are read only by XmlSchema with a resolver that never fetches.
- Hardened later (`req1_2_1_3_noFetchVectorReachesTheNetwork`): the original test only passed because
  `169.254.169.254` is unreachable from a laptop. It now uses `SsrfCanary`, a localhost server that
  counts requests, with all six vectors pointed at it and at the metadata IP. With the fix reverted it
  fails with `GET /xs-import.xsd`; with the fix it gets zero hits. `SsrfCanary` is in the core test-jar
  for the web upload test (Task 10).

## 3. Render: one container, one JVM, one port. PASS (hello image)

- Decision: bind `server.port=${PORT:8080}` on `0.0.0.0`. Mount mock-bank under `/mock-bank/ws` in
  the same JVM (Render forwards only one public port). Free plan: 512 MB RAM, spin-down after 15 min
  idle. JVM flags: `-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k`. In-memory generation only.
- Health check path: `/actuator/health` is avoided to save memory; use a plain `GET /api/health`.
- Evidence: `spikes/render-hello` (multi-stage `eclipse-temurin:21-jdk` -> `21-jre`, JDK HttpServer)
  run with `--memory 512m --memory-swap 512m -e PORT=10000`. It bound `0.0.0.0:10000` and answered
  `{"port":10000,"maxHeapMb":348,...}`: the JVM sees the container limit, and 70% gives ~348 MB heap,
  leaving ~160 MB for metaspace, threads and code cache.
- Not yet measured: Spring Boot (web + generator + mock-bank) resident memory and cold-start time
  under the limit. Check in Task 10 as soon as `web` boots, with `--cpus` limited too, since Render's
  free tier is CPU-limited (exact CPU share not verified here).
- Implications already in the design: stateless `POST /api/generate` returning the zip, in-memory
  zip building, upload size limit, no disk writes.

## 4. OpenAPI: emit YAML directly from the canonical model. DECIDED

- Decision: a small hand-written YAML emitter in `generators`. `io.swagger.parser.v3:swagger-parser:2.1.48`
  is test-scope only, to validate output.
- Why: the spec is small (one POST per operation plus component schemas), and `swagger-models`
  would pull Jackson and more into the generator for no gain.

## 5. Template engine: JMustache 1.16. DECIDED

- Decision: `com.samskivert:jmustache:1.16` for generated proxy and generated MCP server sources.
- Why: a single small jar with no dependencies, logic-less templates keep logic in Java where it is
  testable. Templates live under `generators/src/main/resources/templates/` (watched by the hook).

## 6. Generated zip layout. DECIDED

```
model.json
openapi.yaml
generator-report.json      warnings (code, message, location)
README.md
proxy/                     generated proxy: Spring Boot 3, runtime sources copied in,
                           REST Assured + jqwik tests, its own pom.xml
mcp-server/                generated MCP server: plain Java, mcp 2.1.0, shaded jar, its own pom.xml
```

`POST /api/generate` returns this zip directly. The UI unzips it client-side (vendored JSZip).
