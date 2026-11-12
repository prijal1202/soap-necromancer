# Tasks: Generators

Plan Task 6

- [x] 1. `generators` POM: core, swagger-parser + SnakeYAML (test scope), bank.wsdl fixture
  - _Requirements: 1.9_
- [x] 2. `Names`, `JsonSchemas` (shared OpenAPI / MCP schema mapping, decimal-as-string option)
  - _Requirements: 1.4-1.7, 4.3, 5.4, 6.4_
- [x] 3. `Yaml` emitter with a jqwik round-trip property against SnakeYAML
  - _Requirements: 1.1, 1.9_
- [x] 3a. `OpenApiGenerator` + golden `bank.openapi.yaml` + swagger-parser validation
  - _Requirements: 1.1-1.10_

Plan Task 7

- [x] 4. `SoapClient`, `SoapFaultException`, `SoapTransportException` in `runtime` with tests (stub HttpServer)
  - _Requirements: 2.4, 2.6, 2.9_
- [x] 4a. POM: JMustache, runtime-src resource copy + index (antrun), filtered versions, maven-failsafe
  - _Requirements: 2.2, 7.2_
- [x] 5. `ProxyGenerator`: templates, copied runtime sources, `model.json`, Problem mapping, BigDecimal Jackson flags;
     `Escapes` for untrusted names/docs; in-process javac compile test (bank + hostile model), tagged `fast`
  - _Requirements: 2.1-2.8, 6.1_
- [x] 6. `Generator` + `GeneratedFiles` (layout, report, README, deterministic zip, path validation)
  - _Requirements: 5.1-5.5_
- [x] 7. `GeneratedProjectIT` part 1: mvn package generated proxy, run against mock-bank: GetBalance, Transfer
     (exact 1234567890.123456789 to the ledger), GetStatement, 400 path, SOAP Client fault, invalid JSON
  - _Requirements: 2.6, 6.1, 7.2, 7.3_

Plan Task 8

- [x] 8. `TestGenerator`: stub SOAP server, shared Spring context, contract tests per operation (fixed-seed
     valid requests, unknown field, Client/Server faults, non-SOAP error page), decimal-exactness cases
  - _Requirements: 3.1-3.4, 3.6, 6.3_
- [x] 9. Generated jqwik round-trip properties over model.json (3.1-3.3, 3.6), tagged `fast`
  - _Requirements: 3.5_
- [x] 9a. `GeneratedProjectIT` asserts the generated tests ran inside the generated build (surefire reports,
      zero failures/skips). Mutation check: Client fault mapped to 502 in the template failed every contract class.
  - _Requirements: 3.6, 7.2_

Plan Task 9

- [ ] 10. Decide MCP decimal handling (SDK mapper with BigDecimal, else string schema); record in decisions.md
  - _Requirements: 6.2, 6.4_
- [ ] 11. `McpServerGenerator`: tools.json, main class, stderr logging, README + Kiro snippet
  - _Requirements: 4.1-4.6_
- [ ] 12. `GeneratedProjectIT` part 2: stdio `tools/list` + `tools/call` GetBalance through the generated proxy
  - _Requirements: 4.4, 7.2_
- [x] 13. Extend the Kiro hook to `generators` fast tests (done early, before Task 6 code)
  - _Requirements: 7.1, 7.3_
