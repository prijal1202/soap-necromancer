# SOAP Necromancer

Resurrect legacy SOAP services for REST clients and AI agents. Built for Kiroween 2026
(category: Resurrection) with Kiro specs, steering files and hooks.

Upload a WSDL 1.1 `document/literal` file. The generator parses it and returns a zip with:

- an OpenAPI 3.0 spec,
- a generated proxy (Spring Boot 3, JSON/HTTP to SOAP 1.1 or 1.2, whichever the WSDL binds),
- a generated MCP server (one tool per SOAP operation),
- REST Assured contract tests and jqwik JSON<->XML round-trip tests.

> Live: https://soap-necromancer.onrender.com (Render free tier; the first request after a long idle
> can take up to a minute). See `.kiro/steering/` for scope and architecture.

## Run the generator

```
mvn -pl web -am package -DskipTests
java -jar web/target/soap-necromancer.jar
```

Open http://localhost:8080. It listens on `PORT` (default 8080) and also serves the bundled legacy
bank SOAP service at `/mock-bank/ws` (SOAP 1.1) and `/mock-bank/ws12` (SOAP 1.2). The build downloads a pinned Node to compile the UI; add
`-Dskip.installnodenpm -Dskip.npm` to skip that and keep the last UI build.

## Use the UI

One page: drop a WSDL file (or click **Resurrect the sample bank WSDL**, or **Same bank over SOAP
1.2**), then read the warnings,
browse every generated file in tabs (README, OpenAPI, generated proxy, tests, generated MCP server,
model and report), and download the zip. The browser unpacks the zip itself, so the server still
holds nothing. A 422 shows the same warnings table with the reason the WSDL was rejected. Source and
dev setup: `frontend/README.md`.

```
curl -o bank.wsdl http://localhost:8080/api/sample-wsdl
curl -F wsdl=@bank.wsdl http://localhost:8080/api/generate -o resurrected.zip
```

`/api/sample-wsdl?soap=1.2` returns the same bank with a SOAP 1.2 binding, addressed at
`/mock-bank/ws12` (any other `soap` value is a 400).

## Generator API

`POST /api/generate` accepts a multipart field `wsdl` or a raw `text/xml` body and returns the zip
directly. Nothing is stored. Imported schemas and WSDLs are never fetched; they become
`EXTERNAL_IMPORT` warnings.

**200** `application/zip`. Headers: `X-Generator-Operations`, `X-Generator-Warnings` (count),
`X-Generator-Service`. The warnings themselves are in `generator-report.json` inside the zip:

```json
{
  "generator": "SOAP Necromancer",
  "version": "0.1.0-SNAPSHOT",
  "service": "LegacyBankService",
  "soapVersion": "1.1",
  "operations": ["GetBalance", "Transfer", "GetStatement"],
  "warnings": [
    {
      "code": "XSD_CHOICE",
      "message": "xs:choice is not supported; ...",
      "location": "type[Account]/choice"
    }
  ]
}
```

**Errors** are RFC 7807 `application/problem+json` with `type`, `title`, `status`, `detail`:

| Status | When                                                                      | Extra                          |
| ------ | ------------------------------------------------------------------------- | ------------------------------ |
| 400    | Empty upload, malformed multipart, or multipart without the `wsdl` field  |                                |
| 413    | Upload over 2 MB (`MAX_UPLOAD_BYTES`)                                     |                                |
| 415    | Content type is not multipart, `text/xml` or `application/xml`            |                                |
| 422    | Not a usable WSDL 1.1, or over a resource limit                           | `warnings`                     |
| 429    | More than 30 generations a minute from one client (`GENERATE_PER_MINUTE`) | `Retry-After` header (seconds) |
| 503    | Generator busy for more than 5 s                                          | `Retry-After` header (seconds) |
| 500    | A bug: logged, answered with a fixed message (never a stack trace)        |                                |

A 422 always carries the same `warnings` array as the zip report, so a UI can render both the same way:

```json
{
  "type": "about:blank",
  "title": "WSDL cannot be resurrected",
  "status": 422,
  "detail": "No supported operations: the generator needs a SOAP 1.1 or 1.2 document/literal binding",
  "warnings": [
    {
      "code": "RPC_STYLE",
      "message": "rpc style is not supported; operation skipped",
      "location": "binding[B]/operation[Echo]"
    }
  ]
}
```

**SOAP 1.2:** a `soap12:` binding is handled like a SOAP 1.1 one. The generated proxy then sends
`application/soap+xml; charset=utf-8; action="..."` with no `SOAPAction` header, and reads SOAP 1.2
faults (`Code/Value`, `Reason/Text`; a `Sender` fault is a 400). The Problem body keeps the
`faultcode` and `faultstring` fields for both versions. When a WSDL offers both bindings, the SOAP 1.1
port is used and the others are listed in a `MULTIPLE_PORTS` warning.

Resource limits (all 422): more than 200 operations (`LIMIT_OPERATIONS`), 1,000 reachable types
(`LIMIT_TYPES`), types nested or chained more than 64 deep (`LIMIT_DEPTH`), XML deeper than 256
elements, required fields that contain their own type (`UNSATISFIABLE_RECURSION`), MCP tool schemas
over 20,000 definitions (`LIMIT_TOOL_SCHEMAS`), or output over 16 MB (`LIMIT_OUTPUT`). Optional
recursion, such as a tree, is supported.

**Demo bank:** every visitor shares the in-memory bank at `/mock-bank/ws` (SOAP 1.1) and
`/mock-bank/ws12` (SOAP 1.2); both use one ledger and one rate limit. `GET <path>?wsdl` returns that
path's WSDL. Each path accepts only its own envelope version; the other gets a SOAP 1.1
`VersionMismatch` fault. SOAP 1.2 faults are HTTP 400 for `Sender` and 500 otherwise. It returns to its seeded
accounts every 30 minutes (`MOCKBANK_RESET_INTERVAL`), on restart, and on `POST /mock-bank/reset`
with `Content-Type: application/json`, at most once per 30 s (429 otherwise). Because it is public,
it is bounded: SOAP envelopes up to 64 KB, amounts as plain `xs:decimal` with at most 18 digits on
each side of the point, memos up to 140 characters, the newest 200 statement lines per account, and
120 SOAP calls a minute per client (`MOCK_BANK_PER_MINUTE`). Every failure, including an unexpected
one, is a SOAP fault.

**Hardening of the public site:** a Content-Security-Policy that allows only this origin's scripts,
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and `Referrer-Policy: no-referrer` on every
response; at most 16 request threads, so waiting uploads cannot exhaust the 512 MB heap; multipart
parts kept in memory, so nothing is written to disk.

## Deploying (Render)

`render.yaml` defines one free Docker web service built from `Dockerfile`, with health check
`/api/health`. Render provides `PORT` and `RENDER_EXTERNAL_URL`; the app binds the first and uses the
second as the public mock-bank address in `/api/sample-wsdl` (`PUBLIC_BASE_URL` overrides it).

The free plan gives 0.1 CPU, so the image is tuned for startup: a Class Data Sharing archive built
during `docker build` and a C1-only JIT. Measured at `--cpus 0.1 --memory 512m` it is healthy in
about 23 s (versus 65-86 s for the plain jar), and peaks under 115 MB.

Render spins a free service down after 15 minutes idle. `.github/workflows/keep-alive.yml` pings
`/api/health` every 10 minutes once the repository variable `RENDER_URL` is set. One always-on free
service fits Render's 750 free instance hours a month; GitHub can delay scheduled runs, so an
occasional cold start is still possible.

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
| `web`        | Generator API; serves the UI                                       |
| `frontend/`  | One-page React UI, built into the `web` jar (not a Maven module)   |

## Build

```
mvn verify
```

Requires Java 21 and Maven 3.9+.

## Supported and unsupported

- Supported: SOAP 1.1 and SOAP 1.2 over HTTP, `document/literal`, core XSD subset.
- Unsupported (reported as warnings): rpc/encoded, WS-Security, WS-Addressing, and XSD
  features outside the core subset.

## Future work

- Basic auth and header pass-through for real enterprise endpoints.

## License

See `LICENSE`.
