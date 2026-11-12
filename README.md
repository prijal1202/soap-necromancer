# SOAP Necromancer

Resurrect legacy SOAP services for REST clients and AI agents. Built for Kiroween 2026
(category: Resurrection) with Kiro specs, steering files and hooks.

Upload a WSDL 1.1 `document/literal` file. The generator parses it and returns a zip with:

- an OpenAPI 3.0 spec,
- a generated proxy (Spring Boot 3, JSON/HTTP to SOAP 1.1),
- a generated MCP server (one tool per SOAP operation),
- REST Assured contract tests and jqwik JSON<->XML round-trip tests.

> Status: scaffolding. See `.kiro/steering/` for scope and architecture.

## Modules

| Module       | Purpose                                                            |
| ------------ | ------------------------------------------------------------------ |
| `runtime`    | Model-driven JSON<->XML converter shipped in every generated proxy |
| `core`       | WSDL parser and canonical model                                    |
| `generators` | OpenAPI, generated proxy, generated tests, generated MCP server    |
| `mock-bank`  | Legacy SOAP demo backend                                           |
| `web`        | Generator API and static UI                                        |

## Build

```
mvn verify
```

Requires Java 21 and Maven 3.9+.

## Supported and unsupported

- Supported: SOAP 1.1, `document/literal`, core XSD subset.
- Unsupported (reported as warnings): SOAP 1.2, rpc/encoded, WS-Security, and XSD
  features outside the core subset.

## Future work

- Basic auth and header pass-through for real enterprise endpoints.

## License

See `LICENSE`.
