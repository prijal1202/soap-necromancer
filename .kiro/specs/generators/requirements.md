# Requirements: Generators (OpenAPI, generated proxy, generated tests, generated MCP server)

## Introduction

The `generators` module turns a `ParseResult` (canonical model + warnings) into the files of the
generated zip. It covers plan Tasks 6-9. Everything here is build time: the **generator** emits
files; the **generated proxy** and **generated MCP server** are what those files become at run time.

Generation is pure and in-memory: `ParseResult` in, ordered file map out. No disk, no network,
no state (see `.kiro/steering/tech.md`).

## Glossary

- **Generated project**: the `proxy/` or `mcp-server/` directory in the zip, each a standalone Maven project.
- **Runtime sources**: the `.java` files of the `runtime` module, including the canonical model
  records in `dev.soapnecromancer.runtime.model`. Copied verbatim into the generated proxy.
- **Problem**: an RFC 7807 `application/problem+json` error body.

## Requirements

### Requirement 1: OpenAPI 3.0

**User Story:** As a REST client developer, I want an OpenAPI spec for the legacy service, so that
I can browse it in Swagger UI and generate clients.

#### Acceptance Criteria

1. The generator SHALL emit `openapi.yaml` as OpenAPI 3.0.3, built directly from the canonical model (no swagger-models at runtime).
2. The generator SHALL emit one `POST /{OperationName}` path per operation, with `operationId` = operation name and `summary` = the WSDL documentation (when present).
3. Each path SHALL have a required `application/json` request body referencing the input type, a `200` response referencing the output type, and `400`, `502` and `504` responses referencing a shared `Problem` schema.
4. Complex types SHALL map to `components/schemas` objects with properties in model order, `required` for fields with minOccurs >= 1, and `additionalProperties: false`.
5. Repeated fields SHALL map to arrays with `minItems`, and `maxItems` when bounded.
6. Built-ins SHALL map inline: string -> `string`; int -> `integer/int32`; long -> `integer/int64`; decimal -> `number` with `format: decimal`; boolean -> `boolean`; date -> `string/date`; dateTime -> `string/date-time`. Enums SHALL map to `string` with `enum` values in order.
7. Component names SHALL be the type ids with characters outside `[A-Za-z0-9._-]` replaced, and SHALL be unique.
8. The `servers` entry SHALL default to `http://localhost:8080` (the generated proxy).
9. The output SHALL be deterministic, and SHALL validate without errors using swagger-parser (test scope only).
10. WHEN `bank.wsdl` is generated THEN `openapi.yaml` SHALL equal the golden file.

### Requirement 2: Generated proxy

**User Story:** As a developer, I want a runnable Spring Boot REST service in front of the SOAP
endpoint, so that clients use JSON over HTTP and never see SOAP.

#### Acceptance Criteria

1. The generator SHALL emit `proxy/` as a standalone Maven project (Spring Boot 3.5.0, Java 21) that builds with `mvn package` and needs nothing from this repository.
2. The generator SHALL copy the runtime sources, including the model records, into the generated proxy, and SHALL NOT emit any other model, DTO or JAXB classes. The generated proxy SHALL load `model.json` from its classpath into those records at startup.
3. The generated proxy SHALL expose `POST /{OperationName}` for every operation, matching `openapi.yaml`.
4. For each request the generated proxy SHALL convert the JSON body with `JsonXmlConverter`, wrap it in a SOAP 1.1 envelope, POST it with `Content-Type: text/xml; charset=utf-8` and the operation's `SOAPAction` header, and convert the response payload back to JSON.
5. The SOAP endpoint SHALL default to the WSDL's `soap:address`, and SHALL be overridable with the `SOAP_ENDPOINT` environment variable. Connect and read timeouts SHALL be configurable (defaults 5 s and 30 s).
6. Errors SHALL map to Problem responses: invalid request JSON or a `ConversionException` on the request -> 400 with the JSON path; SOAP fault with a `Client` faultcode -> 400 with `faultcode` and `faultstring`; any other SOAP fault -> 502; unconvertible SOAP response -> 502; timeout -> 504; connection failure -> 502.
7. The generated proxy SHALL NOT forward the backend's raw error body or stack traces to clients.
8. The generated proxy SHALL expose `GET /health`.
9. The SOAP client SHALL be added to `runtime` (JDK `HttpClient`, hardened XML parsing), so it is covered by runtime's dependency budget and copied like the converter.

### Requirement 3: Generated tests

**User Story:** As a developer, I want the generated proxy to ship with tests that prove it works
for this specific WSDL, without needing the real backend.

#### Acceptance Criteria

1. The generator SHALL emit REST Assured contract tests in `proxy/` that start the generated proxy on a random port against an in-test stub SOAP server (JDK `HttpServer`).
2. For each operation, a contract test SHALL check that a valid request returns 200, that the response matches the value the stub returned, and that the stub received the right `SOAPAction` and payload element.
3. For each operation, contract tests SHALL check that an unknown JSON field returns 400 with its path, that a stub `Client` fault returns 400, and that a stub `Server` fault returns 502.
4. Valid requests and stub responses SHALL be generated from the model with the copied `ModelArbitraries`, using a fixed seed, so the tests work for any supported WSDL.
5. The generator SHALL emit jqwik round-trip properties over every operation's input and output types (converter Requirements 3.1-3.3 and 3.6), tagged `fast`, with the try count set by a Maven property.
6. `mvn test` in the generated proxy SHALL pass with no network access beyond localhost.

### Requirement 4: Generated MCP server

**User Story:** As an AI agent user, I want every SOAP operation available as an MCP tool, so that
an agent like Kiro can call the legacy service.

#### Acceptance Criteria

1. The generator SHALL emit `mcp-server/` as a standalone Maven project (plain Java 21, `io.modelcontextprotocol.sdk:mcp:2.1.0`, shaded jar) using the classic sync server and the stdio transport.
2. The generated MCP server SHALL register one tool per operation, named after the operation (characters outside `[A-Za-z0-9_-]` replaced), with the WSDL documentation as its description.
3. Each tool's `inputSchema` SHALL be a JSON Schema (2020-12) derived from the model with the same mapping as Requirement 1.4-1.6. Named complex types SHALL go under `$defs` and be referenced with `$ref`, so recursive types work.
4. A tool call SHALL POST the arguments to `{PROXY_URL}/{OperationName}` (default `http://localhost:8080`) and return the JSON response as text content. On a Problem response it SHALL return `isError: true` with the problem detail.
5. The generated MCP server SHALL write nothing but JSON-RPC to stdout, and SHALL log to stderr only.
6. The generator SHALL emit `mcp-server/README.md` with build steps and a Kiro `mcp.json` snippet (`java -jar ...`, `PROXY_URL` env).

### Requirement 5: Packaging

1. The generator SHALL return files in this layout: `model.json`, `openapi.yaml`, `generator-report.json`, `README.md`, `proxy/**`, `mcp-server/**`.
2. `generator-report.json` SHALL list every parser warning (`code`, `message`, `location`) and the generator version.
3. The zip SHALL be deterministic: entries sorted, fixed timestamps, so the same WSDL always gives identical bytes.
4. Java package and artifact names SHALL be derived from the service name, sanitized to valid identifiers.
5. The generated `README.md` SHALL explain how to run the generated proxy and the generated MCP server, and list the warnings.

### Requirement 6: Decimal exactness end to end

**User Story:** As a bank, I want a transfer of 1234567890.123456789 to arrive at the SOAP backend
with every digit, so that money is never rounded by a JSON layer.

#### Acceptance Criteria

1. The generated proxy SHALL configure Jackson with `USE_BIG_DECIMAL_FOR_FLOATS` and `WRITE_BIGDECIMAL_AS_PLAIN`, so decimals never pass through `double`.
2. The generated MCP server SHALL parse tool arguments so that decimals are `BigDecimal`, and SHALL forward them to the generated proxy without exponent notation.
3. Generated contract tests SHALL send `0.10` and `1234567890.123456789` to a decimal field (when the model has one) and assert that the stub received exactly that text.
4. WHEN the MCP SDK cannot be configured to produce `BigDecimal` THEN decimal fields in the MCP `inputSchema` SHALL be `type: string` with a decimal pattern instead of `type: number` (decided in Task 9, recorded in `docs/decisions.md`).

### Requirement 7: Verification layers

1. Generator unit tests (golden files, schema validation, rendered file list) SHALL be tagged `fast` and run by the Kiro hook when templates change.
2. An integration test (`*IT`, maven-failsafe, `verify` phase only) SHALL generate from `bank.wsdl`, run `mvn package` on both generated projects, start mock-bank, the generated proxy and the generated MCP server, and call GetBalance and Transfer end to end, including via MCP `tools/call`.
3. The Kiro hook SHALL NOT run the integration test. It runs the `test` phase only, so failsafe tests are excluded by construction.
