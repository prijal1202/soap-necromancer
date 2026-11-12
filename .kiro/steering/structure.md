# Structure

Maven multi-module build. Root package: `dev.soapnecromancer`.

```
pom.xml            parent POM (Spring Boot BOM import, pinned versions)
runtime/           model-driven JSON<->XML converter + SOAP client; copied into every generated proxy
core/              WSDL parser, canonical model, unsupported-feature warnings
generators/        OpenAPI, generated proxy, generated tests, generated MCP server
mock-bank/         legacy SOAP 1.1 demo backend (library, mounted in-process by web)
web/               generator API (POST /api/generate returns the zip), static UI, hosts mock-bank
frontend/          placeholder; default UI is static HTML under web/
docs/decisions.md  spike outcomes (one decision per topic)
spikes/            throwaway spike code
.kiro/steering/    product.md, tech.md, structure.md
.kiro/specs/       parser, converter, generators
.kiro/hooks/       jqwik fast-test hook
```

## Module dependencies

- `runtime` -> none (JDK only). Owns the canonical model records
  (`dev.soapnecromancer.runtime.model`) so the generated proxy and the generator share them.
- `core` -> `runtime` (produces the canonical model).
- `generators` -> `core`, `runtime` (runtime sources are copied into the generated proxy).
- `mock-bank` -> none of the generator modules. Its `bank.wsdl` is the shared test fixture.
- `web` -> `core`, `generators`, `mock-bank`.

## Generated zip layout

```
model.json
openapi.yaml
generator-report.json
proxy/        generated proxy (runtime sources copied in) + REST Assured and jqwik tests
mcp-server/   generated MCP server
README.md
```
