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
- Measured in Task 10 (`spikes/render-web/Measure.java`): the packaged web app (generator API +
  mock-bank, one JVM) with the JVM sized for a 512 MB, single-CPU container
  (`-XX:MaxRAM=512m -XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:ActiveProcessorCount=1`):

  | Metric                                 | Result                                  |
  | -------------------------------------- | --------------------------------------- |
  | Healthy after process start            | 3.2 s (Spring reports started in 2.4 s) |
  | RSS after start / after 28 generations | 133 MB / 146 MB                         |
  | First bank generation / warm           | 370 ms / 13-46 ms, 46 KB zip, 42 files  |
  | 8 concurrent uploads                   | all 200                                 |

  Verdict: about 30% of the 512 MB budget, so no memory tuning is needed. Not covered: CPU throttling.
  This ran on the host JVM, not in a container, because the Docker CLI hung during the check. The real
  `--memory 512m --cpus 0.1` container run is in section 3.4: CPU, not memory, dominates there.

- Design change from the measurement: the generation cap (`necromancer.max-concurrent=4`) first
  rejected a burst immediately (4 of 8 got 503) although a generation takes ~20 ms. It now waits up
  to 5 s for a slot and returns 503 only after that. Test: `burstOfConcurrentUploadsAllSucceed`.
- Measurement pitfall, now guarded: the first run reported healthy after 294 ms because a leftover
  app instance from an aborted run was still on the port and answered for it. The harness now
  refuses to start if the port is busy and prints the measured app's own "Started" log line.
- Implications already in the design: stateless `POST /api/generate` returning the zip, in-memory
  zip building, upload size limit, no disk writes.

### 3.4 Cold start in a real container at Render free limits. PASS

- Decision: the `Dockerfile` builds a CDS archive (Spring Boot 3.5 recipe: `jarmode=tools extract`,
  training run with `-XX:ArchiveClassesAtExit` and `spring.context.exit=onRefresh`) and runs with
  `-XX:SharedArchiveFile` and `-XX:TieredStopAtLevel=1`. Training and runtime share one
  `JAVA_TOOL_OPTIONS`, so the archive matches. Lazy initialization is not used.
- Why: memory was never the constraint (section 3 above); CPU is. At 0.1 CPU, Spring's context
  refresh alone took 26.5 s on the plain jar, and C2 compiler threads compete for the same share.
- Evidence: `spikes/render-web/ContainerMeasure.java` runs the image detached with
  `--memory 512m --memory-swap 512m --cpus 0.1 -e PORT=10010`, reads the cgroup memory files, and
  times 10 sequential bank generations plus 4 concurrent ones. Time is from `docker run` to the first
  200 from `/api/health`.

  | Image                             | Healthy after  | Spring "Started in" | cgroup peak | First / warm generate |
  | --------------------------------- | -------------- | ------------------- | ----------- | --------------------- |
  | Plain jar (previous `Dockerfile`) | 65 s\*, 86 s   | 54 s, 67 s          | 164 MB      | 5.9 s / 0.4-1.1 s     |
  | + CDS                             | 41 s, 46 s     | 35 s, 38 s          | 113-119 MB  | 4.0-4.6 s / 0.3-0.9 s |
  | + CDS + lazy init                 | 41 s           | 34 s                | 123 MB      | 3.6 s / 0.3-1.0 s     |
  | + CDS + C1 only (current)         | 23.6 s, 23.4 s | 18.6 s, 19.0 s      | 95-111 MB   | 2.2-3.0 s / 0.2-0.6 s |

  \* process uptime from the app's own log line; that run's harness lost its `docker run` client.
  All runs: 4 concurrent uploads all 200, no OOM events. One host, few samples: treat the numbers as
  ranges, not benchmarks.

- Lazy init rejected: no measurable gain on top of CDS, and it moves bean failures from startup to
  the first request, which would hide a broken deploy behind a passing health check.
- C1 only costs peak throughput for long-running hot code; generation here is short and warm
  generations got faster, not slower, at 0.1 CPU.
- Not covered: Render's own image pull and scheduling time before the JVM starts (Render quotes
  about a minute for a free spin-up). The keep-alive workflow (`.github/workflows/keep-alive.yml`,
  every 10 min against the 15 min idle spin-down) avoids most cold starts; 750 free instance hours a
  month cover one always-on service (https://render.com/docs/free).
- Harness pitfall: an attached `docker run` client dropped its connection after 18 s on Docker
  Desktop while the container kept running, which ended a measurement with "healthy=false". The
  harness now runs detached and polls `docker inspect`.

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

## 7. Decimals in the generated MCP server (Task 9). PASS

- Decision: the generated MCP server builds its stdio transport with
  `new JacksonMcpJsonMapper(JsonMapper)` (Jackson 3) configured with `USE_BIG_DECIMAL_FOR_FLOATS`,
  `JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES` disabled and `WRITE_BIGDECIMAL_AS_PLAIN`, and
  forwards arguments to the generated proxy with the same mapper. Decimal fields stay `type: number`
  in the tool `inputSchema`; the string fallback (generators Requirement 6.4) is not needed.
- Evidence: `spikes/mcp-stdio` `DecimalMcpServer` + `DecimalDriver`, same tool, two mappers:

  | Sent                   | SDK default mapper            | Configured mapper                 |
  | ---------------------- | ----------------------------- | --------------------------------- |
  | `0.10`                 | `Double 0.1`                  | `BigDecimal 0.10`                 |
  | `1234567890.123456789` | `Double 1.2345678901234567E9` | `BigDecimal 1234567890.123456789` |
  | `2500.00`              | `Double 2500.0`               | `BigDecimal 2500.00`              |
  | `100`                  | `Integer 100`                 | `Integer 100`                     |

- Also observed: the SDK validates arguments against `inputSchema` before the handler runs, so a
  string `"12.50"` for a `number` field is rejected with `isError: true`. The schema is the contract
  the agent sees, so this is the behaviour we want.
