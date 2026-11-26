# WSDLForge: Devpost submission draft

Draft for the Kiroween 2026 submission (category: Resurrection). Every claim below has its evidence
in `docs/writeup-notes.md`, `docs/decisions.md` or the tests named. Edit freely; keep claims matched
to the evidence.

---

## Tagline

Dead SOAP services in, working REST APIs and MCP tools for AI agents out.

## Inspiration

A lot of the world still runs on SOAP: banks, insurers, airlines, governments. Those services work,
but nothing modern can talk to them. A REST client wants JSON, and an AI agent wants MCP tools,
not XML envelopes described by a WSDL file from 2006. The usual fix is to hand-write a wrapper for
every service, and the wrappers rot as fast as the services they wrap.

We wanted to raise these services from the dead in one step: upload the WSDL, get back a REST API,
an MCP server and the tests that prove nothing was lost in between, especially money.

## What it does

Upload a WSDL 1.1 `document/literal` file (or click **Try Demo Bank Service**). WSDLForge parses it
and returns one zip with:

- an **OpenAPI 3.0 spec** for the service,
- a **generated proxy**: a Spring Boot 3 app that takes JSON over HTTP and calls the SOAP service,
  over SOAP 1.1 or 1.2, whichever the WSDL binds,
- a **generated MCP server** with one tool per SOAP operation, so an agent like Kiro can call the
  legacy service directly,
- **generated tests**: REST Assured contract tests per operation, and jqwik property tests that
  check every JSON ↔ XML round trip for that service's own types,
- a **report** of every WSDL feature it skipped, with its exact location and the reason.

Before downloading anything, the page previews the result in five tabs: the conversion report, the
OpenAPI endpoints and schemas, the MCP tools with a copyable `mcp.json` entry for Kiro, a sample
JSON request next to the exact SOAP envelope the proxy sends, and every generated file.

The site hosts a legacy bank SOAP service of its own, so you can run the generated proxy and MCP
server against it and let Kiro move money through a 20-year-old protocol.

Nothing is stored. Generation runs in memory and the zip goes straight back to the browser.

## How we built it

- **Java 21 and Maven**, five modules:
  - `runtime`: the JSON ↔ XML converter and SOAP client, JDK only and copied into every generated
    proxy,
  - `core`: the WSDL parser,
  - `generators`: OpenAPI, the generated proxy, the generated tests, the generated MCP server,
  - `mock-bank`: the legacy demo service,
  - `web`: the generator API.
- **No code generation from schemas (no JAXB).** One small converter reads the service's model at
  run time, so the same code serves every WSDL and every test of it covers all of them.
- **React 19 + Vite UI**, served from the same jar. The browser unpacks the zip itself.
- **The MCP Java SDK** (stdio) for the generated MCP server.
- **One Docker container on Render's free tier**, tuned for 0.1 CPU: a class-data-sharing archive
  and a C1-only JIT cut cold start from 65-86 s to about 23 s.

## How we used Kiro

**Specs before code.** The parser, converter and generators each have a Kiro spec:
`requirements.md` in EARS form, `design.md` and `tasks.md`. Together they hold 17 requirements and
134 acceptance criteria, and tests are named after the criterion they check
(`req1_2_1_3_noFetchVectorReachesTheNetwork`). Writing the requirement first is what found our
worst bug: parser Requirement 1.2 says "the parser SHALL NOT fetch any import location". Its test
showed that the WSDL library fetched imports anyway (details below).

**Steering files** (`.kiro/steering/`): `product.md` fixes the terminology ("generator", "generated
proxy", "generated MCP server", never "proxy" alone), `tech.md` holds the invariants (stateless
generation, no JAXB, XXE hardening, pinned versions), and `structure.md` holds the module layout and
zip layout. Every session started from the same rules, so the code, specs and README agree.

**A hook** (`.kiro/hooks/jqwik-fast-roundtrip.json`): on every save in `runtime`, `core`,
`generators` or the templates, Kiro runs the fast-tagged tests, including the jqwik round-trip
properties with a capped try count. The slow end-to-end tests that build generated projects never
run from the hook.

**Agent reviews.** Before launch we ran a whole-repo production review as four parallel read-only
reviews (runtime, core, generators, web and UI). Every High and Medium finding was fixed with a
regression test written first. Several of the bugs below came from that review.

## Challenges we ran into

The tests caught real bugs. The ones worth telling:

1. **SSRF through the WSDL library.** The test for "never fetch imports" pointed an `xs:import` at
   the cloud metadata address `169.254.169.254`. WSDL4J tried to connect even with imports switched
   off, which on Render would have been a server-side request forgery hole in a public upload form.
   The fix: WSDL4J never sees the schemas at all. A localhost canary server now proves it: with the
   fix reverted it records `GET /xs-import.xsd`; with the fix, zero requests. The production review
   later found a second door (a second `wsdl:types` block), now closed and tested the same way.
2. **The MCP SDK would have rounded money.** With the SDK's default JSON mapper, an agent's transfer
   of `1234567890.123456789` arrived as a `double`. We planted the default back to prove it: the
   bank received `1234567890.1234567`. The generated MCP server now ships a configured mapper, and
   an end-to-end test sends that amount through an MCP `tools/call` and checks the ledger.
3. **A 13-byte number could kill the demo.** `1E+300000000` as a transfer amount made the bank
   rescale a balance by 10^300000000 while holding the ledger lock: out of memory, then a dead JVM.
   The same trick hit every generated proxy. Numbers are now bounded before any arithmetic.
4. **Prompt injection through the WSDL.** WSDL documentation went verbatim into MCP tool
   descriptions, which an agent reads as guidance, in a demo where the agent moves money. Tool
   descriptions now lead with the generator's own sentence and quote the WSDL text as bounded data.
5. **Code injection through comments.** WSDL text lands in Java comments, and javac processes
   unicode escapes even inside comments, so a WSDL could inject code into the generated proxy. A
   test with hostile names proves the generated project still compiles safely.
6. **Cold starts at 0.1 CPU.** On a laptop the app was healthy in 3.2 s; in a container at Render's
   free-tier limits it took 65-86 s. Memory was never the problem; CPU was. Measuring the real
   limits, not the host, got it to about 23 s.

## Accomplishments that we're proud of

- **Exact money, end to end.** `1234567890.123456789` travels JSON → generated proxy → SOAP → bank
  and back with every digit, over SOAP 1.1 and 1.2, and through an MCP tool call.
- **Generated tests that can fail.** Each generated proxy ships its own tests (20 for the demo
  bank). We changed the template to map a client fault to the wrong status, and the generated build
  failed for every operation.
- **278 tests in the generator itself**, including 16 end-to-end tests that build the generated
  proxy and MCP server with Maven, start them, and run transfers against the bank.
- **A public site that holds up**: a strict Content-Security-Policy, upload and resource limits,
  rate limits, XXE and SSRF hardening, and a shared demo bank with bounded inputs that resets
  itself every 30 minutes.
- **The preview shows the real bytes.** The JSON ↔ SOAP tab is produced by the same converter and
  SOAP client code the generated proxy runs, and a test posts that envelope to the bank.

## What we learned

- Writing the EARS requirement before the code found more than any amount of testing after it. The
  SSRF bug existed because the requirement said what must never happen, and the test took it
  literally.
- Property tests need proof that they can fail. We planted bugs to check ours: a whitespace trim was
  caught in one or two tries and shrunk to a minimal case.
- Measure under the real limits. Our cold-start number on a laptop (3.2 s) was 20 to 27 times too
  optimistic for the real container (65-86 s).

## What's next

- Upload a zip of a WSDL plus its XSD files, resolving imports only from inside the zip, still with
  no network fetches.
- Basic auth, WS-Security UsernameToken and SOAP header pass-through for real enterprise endpoints.
- Attributes, `xs:choice` and rpc/literal.
- A choice of SOAP version when a WSDL offers both.

## Built with

Java 21, Spring Boot 3, Maven, WSDL4J, Apache XmlSchema, MCP Java SDK, Jackson, JMustache, jqwik,
JUnit 5, REST Assured, React 19, TypeScript, Vite, WebGL, Docker, Render, Kiro.

## Try it

- Live: https://wsdlforge.onrender.com (free tier; the first request after a long idle can take up
  to a minute)
- Source: https://github.com/prijal1202/wsdl-forge

---

## Before submitting

- [ ] Check the Devpost form's section names and length limits; split or trim to fit.
- [ ] Decide "we" or "I" to match the team.
- [ ] Add the demo video link.
- [ ] Set up the external uptime monitor, so judges do not hit a cold start.
