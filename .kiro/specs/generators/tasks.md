# Tasks: Generators

Plan Task 6
- [ ] 1. `generators` POM: JMustache, runtime-src resource copy + index, swagger-parser (test), failsafe
  - _Requirements: 2.2, 7.2_
- [ ] 2. `Names`, `JsonSchemas` (shared OpenAPI / MCP schema mapping)
  - _Requirements: 1.4-1.7, 4.3, 5.4_
- [ ] 3. `OpenApiGenerator` + golden `bank.openapi.yaml` + swagger-parser validation
  - _Requirements: 1.1-1.10_

Plan Task 7
- [ ] 4. `SoapClient`, `SoapFaultException`, `SoapTransportException` in `runtime` with tests (stub HttpServer)
  - _Requirements: 2.4, 2.6, 2.9_
- [ ] 5. `ProxyGenerator`: templates, copied runtime sources, `model.json`, Problem mapping, BigDecimal Jackson flags
  - _Requirements: 2.1-2.8, 6.1_
- [ ] 6. `Generator` + `GeneratedFiles` (layout, report, README, deterministic zip)
  - _Requirements: 5.1-5.5_
- [ ] 7. `GeneratedProjectIT` part 1: mvn package generated proxy, run against mock-bank, GetBalance + Transfer
  - _Requirements: 7.2, 7.3_

Plan Task 8
- [ ] 8. `TestGenerator`: stub SOAP server, contract tests per operation, decimal-exactness contract cases
  - _Requirements: 3.1-3.4, 3.6, 6.3_
- [ ] 9. Generated jqwik round-trip properties over model.json
  - _Requirements: 3.5_

Plan Task 9
- [ ] 10. Decide MCP decimal handling (SDK mapper with BigDecimal, else string schema); record in decisions.md
  - _Requirements: 6.2, 6.4_
- [ ] 11. `McpServerGenerator`: tools.json, main class, stderr logging, README + Kiro snippet
  - _Requirements: 4.1-4.6_
- [ ] 12. `GeneratedProjectIT` part 2: stdio `tools/list` + `tools/call` GetBalance through the generated proxy
  - _Requirements: 4.4, 7.2_
- [ ] 13. Extend the Kiro hook to `generators` fast tests
  - _Requirements: 7.1, 7.3_
