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

## More things the tests caught (Task 7)

3. **Code injection through WSDL documentation (guarded, then proven).** WSDL text lands in Java
   comments of the generated proxy. javac processes unicode escapes even inside comments, so a
   backslash-u escape for a newline or for the comment terminator in a WSDL could inject code.
   `Escapes.javaComment` neutralizes the backslash. Two proofs: writing such an escape literally
   in the Javadoc of `Escapes.java` itself broke the generator's own compilation ("unclosed string
   literal"); and with the backslash escaping removed, `hostileNamesAndDocumentationStillCompile`
   failed because the generated controller no longer compiled.

4. **Jackson's JsonNode silently changes decimals.** `GeneratedProjectIT` read `"balance":2500.00`
   from the generated proxy and got `2.5E+3`: the default `JsonNodeFactory` strips trailing zeros.
   The generated proxy was correct (raw wire text `2500.00`); the test's parser was not. The fix
   (`withExactBigDecimals(true)`) is now a requirement for the generated MCP server (generators
   Requirement 6.2), which is where a JsonNode would otherwise have corrupted bank amounts.

5. **End-to-end exactness.** `GeneratedProjectIT` transfers `1234567890.123456789` through
   JSON -> generated proxy -> SOAP -> mock-bank and asserts the ledger holds exactly that value.
