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

## Generated tests have teeth (Task 8)

Every generated proxy ships its own tests: REST Assured contract tests per operation against a stub
SOAP server, plus jqwik round-trip properties over its own `model.json`. For bank.wsdl that is 20
tests, all passing inside the generated project's `mvn package`. To check they can fail, the proxy
template was changed to map SOAP Client faults to 502 instead of 400: the generated
`soapClientFaultIs400WithTheFault` failed for every operation, which broke the generated build.

## The MCP SDK's default JSON mapper would have rounded money (Task 9)

Requirement 6.2 (written before the code) said tool arguments must arrive as `BigDecimal`. A spike
checked it: with the MCP Java SDK's default mapper, an agent's transfer of `1234567890.123456789`
arrives as `Double 1.2345678901234567E9`, so the last digits would never reach the bank, and
`0.10` becomes `0.1`. With a configured `JacksonMcpJsonMapper` both arrive exactly. The generated
MCP server ships the configured mapper, and the end-to-end test sends that amount through an MCP
`tools/call` and checks the mock-bank ledger. See `docs/decisions.md` section 7.

Proof it matters end to end: with the SDK's default mapper planted back into the generated MCP
server, `GeneratedProjectIT.mcpTransferKeepsEveryDigit` failed. The agent asked to transfer
`1234567890.123456789`; the bank received `1234567890.1234567` and reported a new balance of
`-1234567740.1234567`. With the configured mapper, every digit arrives.

## Hardening the public endpoint (Task 10 follow-up)

6. **Recursive WSDLs would have broken every generated proxy's own tests.** Writing a round-trip
   property for a tree type (`RecursiveTypeProperties`) before touching code produced a
   `StackOverflowError` straight away: `ModelArbitraries` built arbitraries eagerly, recursing
   forever. Every generated proxy ships that helper, and the random-model generator only ever built
   acyclic models, so nothing had caught it. It now cuts recursion once a type repeats on a path.

7. **A 2 MB upload could crash the generator through the type graph, not the XML.** A chain of 5,000
   named types (`T0 -> T1 -> ...`) is shallow XML but drives the schema walker 5,000 levels deep. On
   Render's 512 KB stack that is a `StackOverflowError`, a 500. The parser now checks depth and type
   count while walking. Proof: with the depth check disabled, `req5_4_longChainCannotOverflowA512kStack`
   failed with a real `StackOverflowError`; with it, the upload is a clean 422 `LIMIT_DEPTH`.

8. **MCP tool schemas grow quadratically.** Each tool embeds every type it reaches, so 200 operations
   sharing 1,000 types means 200,000 schema definitions, built in memory before any size check could
   see them. The generator now counts first and refuses (`LIMIT_TOOL_SCHEMAS`) in milliseconds.

## Cold start on 0.1 CPU (Task 12 prep)

9. **Measure the real limits, not the host.** On the host JVM sized to 512 MB the app was healthy
   in 3.2 s. In a container at Render free's 0.1 CPU the same jar took 65-86 s, enough for a judge to
   give up. Memory stayed under 165 MB throughout; CPU was the whole problem. A CDS archive built in
   the Dockerfile plus a C1-only JIT brought it to about 23 s (two runs), with a lower memory peak.
   Spring lazy init was measured too and dropped: no gain, and it would let a broken bean pass the
   health check. Numbers and method: `docs/decisions.md` section 3.4.
