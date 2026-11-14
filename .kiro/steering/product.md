# Product: SOAP Necromancer

SOAP Necromancer resurrects legacy SOAP services for REST clients and AI agents.
Kiroween 2026, category: Resurrection.

## Terminology (use everywhere: specs, code comments, README, tasks)

- **generator**: the build-time tool in this repo (`core`, `generators`, `web`).
- **generated proxy**: the Spring Boot 3 REST service the generator emits.
- **generated MCP server**: the standalone MCP server the generator emits.
- Never write "proxy" or "MCP server" unqualified when the meaning is ambiguous.

## What it does

Upload a WSDL 1.1 `document/literal` file. The generator parses it into a canonical model
and produces, as one in-memory zip:

- an OpenAPI 3.0 spec,
- a generated proxy (JSON/HTTP in, SOAP 1.1 out),
- a generated MCP server (one tool per SOAP operation, typed JSON Schema inputs),
- REST Assured contract tests and jqwik JSON<->XML round-trip property tests,
- `generator-report.json` with warnings.

## Scope

- In: SOAP 1.1 and SOAP 1.2 over HTTP, document/literal, no auth. When a WSDL offers both, the
  SOAP 1.1 port is used.
- Unsupported (shown as warnings in the UI): rpc/encoded, WS-Security, WS-Addressing.
- XSD core subset: simple/complex types, sequences, minOccurs/maxOccurs, enums,
  string/int/decimal/boolean/date/dateTime, one target namespace, simple imports.
  Anything else yields a warning with a location.
- Stretch only: `xs:choice`, attributes.
- Future work (README only): Basic auth, header pass-through.

## Demo

Upload the bank WSDL, download the zip, run the generated proxy and generated MCP server,
and let an AI agent (Kiro) run a transfer against the bundled `mock-bank` SOAP service.
