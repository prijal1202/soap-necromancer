# Tasks: WSDL Parser and Canonical Model

- [x] 1. Model records in `runtime` (`ServiceModel`, `OperationModel`, `ElementRef`, `TypeModel`, `FieldModel`, `BuiltIn`)
  - _Requirements: 3.1, 3.3, 3.4, 3.6_
- [x] 2. `SecureXml` hardened DOM parsing and `WsdlParseException`
  - _Requirements: 1.1, 1.4_
- [x] 3. WSDL structure: services, ports, SOAP 1.1/1.2, style/use, parts, headers, one-way, multiple ports
  - _Requirements: 2.1-2.8, 2.10-2.12_
- [x] 4. WS-Security / WS-Policy detection and external import warnings without fetching
  - _Requirements: 1.2, 1.3, 2.9_
- [x] 5. XSD walker: built-ins, sequences, occurs, enums, refs, forms, unsupported constructs, unique ids
  - _Requirements: 3.1-3.10_
- [x] 6. `ModelJson` deterministic serialization
  - _Requirements: 4.2_
- [x] 7. Tests: golden `bank.wsdl`, negative fixtures per warning group, fatal cases
  - _Requirements: 4.1, 4.3, and all warning codes_
- [x] 8. Hardening from the production review: every `wsdl:types` stripped before WSDL4J plus a refusing locator; every processing error is fatal or a warning; document-order port and binding choice; unsupported bindings, missing address, duplicate and invalid operation names, header parts, invalid element names and occurrence bounds
  - _Requirements: 1.5, 1.6, 2.6, 2.10, 2.13-2.17, 3.12, 3.13_
- [x] 9. SOAP 1.2 document/literal: `SoapVersion` in the model, a `SoapBinding` adapter over `soap:` and `soap12:` elements, version preference across ports
  - _Requirements: 2.2, 2.3, 2.8, 2.10, 2.12, 2.15_
