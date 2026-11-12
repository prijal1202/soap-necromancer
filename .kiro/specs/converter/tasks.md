# Tasks: Model-driven JSON <-> XML Converter

- [x] 1. `ConversionException`, `RuntimeXml` (hardened parser, escaping, XML 1.0 char check)
  - _Requirements: 1.12, 1.13, 2.8_
- [x] 2. `JsonXmlConverter.toXml` with validation and namespace declarations
  - _Requirements: 1.1-1.11_
- [x] 3. `JsonXmlConverter.toJson` (lenient read, canonical output)
  - _Requirements: 2.1-2.7_
- [x] 4. Example tests: empty list, special characters, CR, decimals, invalid chars, input errors
  - _Requirements: 1.4-1.13, 2.1-2.7_
- [x] 5. jqwik `ModelArbitraries` + `XmlRenderer`; properties for 3.1-3.3, tagged `fast`; tries via Maven property
  - _Requirements: 3.1-3.3, 3.5_
- [x] 6. bank.wsdl round-trip properties in `core` via runtime test-jar
  - _Requirements: 3.4_
- [x] 7. Enforcer dependency budget on `runtime`; Kiro hook running fast tests on save
  - _Requirements: 3.5, 4.1_
- [x] 8. Decimal exactness property (31 digits, scale 0..20, trailing zeros) and bank-amount examples
  - _Requirements: 3.6_
