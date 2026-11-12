# Notes for the Devpost write-up (Task 13)

Facts collected during the build, with the evidence for each. Keep claims matched to the evidence.

## What the tests caught

1. **SSRF in WSDL4J (real bug, found by an example-based security test).** The parser spec required
   "the parser SHALL NOT fetch any import location" (parser Requirement 1.2), written before the
   code. Its test pointed an `xs:import` at `169.254.169.254` (the cloud metadata address). WSDL4J
   tried to connect even with `javax.wsdl.importDocuments=false`. On Render that endpoint would have
   been a server-side request forgery hole in a public upload form. Fix: WSDL4J never sees
   `wsdl:types`. The test was then hardened with a localhost canary server: with the fix reverted it
   records `GET /xs-import.xsd`; with the fix, zero requests. See `docs/decisions.md` section 2.
   This one came from writing the EARS requirement first, not from jqwik.

2. **Property tests catch converter regressions (planted bug).** To check the jqwik properties could
   fail, a bug was planted that trimmed whitespace from string values on read. Two of three
   properties failed within one or two tries and shrank to a minimal case. The third
   (`absentOptionalsProduceNoElements`) passed, which is expected: it checks which elements
   exist, not their values.

## Decimal exactness

Bank amounts such as `0.10` and `1234567890.123456789` are a property: 31 significant digits,
scale up to 20, same digits and same scale after JSON -> XML -> JSON. The generators spec
(Requirement 6) carries this through the generated proxy's and the generated MCP server's JSON layers.
