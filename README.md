# SOAP Necromancer

Resurrect legacy SOAP services for REST clients and AI agents. Built for Kiroween 2026
(category: Resurrection) with Kiro specs, steering files and hooks.

Upload a WSDL 1.1 `document/literal` file. The generator parses it and returns a zip with:

- an OpenAPI 3.0 spec,
- a generated proxy (Spring Boot 3, JSON/HTTP to SOAP 1.1),
- a generated MCP server (one tool per SOAP operation),
- REST Assured contract tests and jqwik JSON<->XML round-trip tests.

> Status: generator, generated proxy, generated MCP server and web API work end to end.
> UI and deployment are in progress. See `.kiro/steering/` for scope and architecture.

## Run the generator

```
mvn -pl web -am package -DskipTests
java -jar web/target/soap-necromancer.jar
```

It listens on `PORT` (default 8080) and also serves the bundled legacy bank SOAP service at
`/mock-bank/ws`.

```
curl -o bank.wsdl http://localhost:8080/api/sample-wsdl
curl -F wsdl=@bank.wsdl http://localhost:8080/api/generate -o resurrected.zip
```

`POST /api/generate` accepts a multipart field `wsdl` or a raw `text/xml` body and returns the zip
directly. Nothing is stored. Errors are `application/problem+json`: 400 for an empty upload,
413 for an upload over 2 MB (`MAX_UPLOAD_BYTES`), 415 for a wrong content type, and 422 for a
document that is not a usable WSDL 1.1 (the warnings explain why). Imported schemas and WSDLs
are never fetched; they become `EXTERNAL_IMPORT` warnings.

## Pointing at a real SOAP endpoint

Generate from the real WSDL, then start the generated proxy with `SOAP_ENDPOINT` set to the
service URL. The generated MCP server only needs `PROXY_URL`.

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
