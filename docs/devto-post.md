---
title: "I raised dead SOAP services for AI agents with Kiro (and the spec found my worst bug)"
published: true
description: "Building WSDLForge for Kiroween 2026: WSDL in, REST API, MCP server and tests out. What spec-driven development with Kiro caught that I would have shipped."
tags: kiro, kiroween, java, ai
cover_image: https://raw.githubusercontent.com/prijal1202/wsdl-forge/main/docs/images/cover-strike.jpg
---

> This post is being written as part of the **Kiroween 2026 hackathon** (Kiroween Hackathon 2026). It's about how I used Kiro to build my entry, [WSDLForge](https://wsdlforge.onrender.com) (category: Resurrection). The code is on [GitHub](https://github.com/prijal1202/wsdl-forge).

Banks, insurers, airlines and governments still run on SOAP. Those services work fine. The problem is that nothing modern can talk to them. A REST client wants JSON. An AI agent wants MCP tools. What the service offers is a WSDL file from 2006 and XML envelopes.

So for Kiroween's "bring dead technology back to life" category, I built a generator: upload a WSDL, get back one zip with

- an OpenAPI 3.0 spec,
- a Spring Boot proxy that takes JSON and speaks SOAP 1.1 or 1.2 to the real service,
- an MCP server with one tool per SOAP operation, so an agent like Kiro can call the service,
- contract tests and property-based tests for that specific service.

The site even hosts its own legacy bank SOAP service, so you can let Kiro move money through a 20-year-old protocol.

![The JSON to SOAP preview: a REST request next to the exact SOAP 1.1 envelope, with 1234567890.123456789 highlighted in both](https://raw.githubusercontent.com/prijal1202/wsdl-forge/main/docs/images/tab-sample.png)
_The JSON ↔ SOAP tab: the REST request, and the exact envelope the generated proxy sends for it. The amount keeps every digit._


This post is about how I built it with Kiro, and mostly about the bugs. Several of them I would have shipped without the way Kiro pushed me to work.

## Specs first, code second

Kiro's spec workflow gives you three files per feature: `requirements.md`, `design.md` and `tasks.md`. Requirements are written in EARS form ("WHEN ... THEN the system SHALL ..."). I wrote three specs, for the parser, the JSON ↔ XML converter and the generators: 17 requirements and 134 acceptance criteria in total. Tests are named after the criterion they check, so a failing test tells you which promise broke.

It felt slow at first. Then this happened.

### The requirement that found an SSRF hole

The hosted generator accepts uploads from anyone, so the parser spec starts with security. One line in it:

```markdown
2. The parser SHALL NOT fetch any `wsdl:import`, `xs:import` or `xs:include` location over the
   network or file system.
```

The test for it pointed an `xs:import` at `169.254.169.254`, the cloud metadata address. I expected it to pass, because I had switched off import fetching in WSDL4J, the WSDL library.

WSDL4J tried to connect anyway. It still resolves schema imports inside `wsdl:types` even with `javax.wsdl.importDocuments=false`. On Render, that would have been a server-side request forgery hole in a public upload form.

The fix: WSDL4J never sees the schemas at all. The parser strips `wsdl:types` before handing the document over and reads the schemas with a resolver that refuses every fetch.

The test had a weakness too: it only passed because the metadata IP is unreachable from a laptop. So it became a canary. A tiny localhost HTTP server counts requests, and every import vector points at it. With the fix reverted, the canary records `GET /xs-import.xsd`. With the fix, zero requests.

Later, a review found a second door: WSDL4J reads _every_ `wsdl:types` element, and the parser only stripped the first. A second, empty-looking block reopened the fetch. The canary test now puts its payload in the second block.

Without the requirement, nobody would have written that test. "It doesn't fetch imports" was something I believed, not something I had checked.

## Steering: one set of rules for every session

Kiro's steering files sit in `.kiro/steering/` and load into every conversation. I kept three:

- **`product.md`**: scope and terminology. It defines "generator", "generated proxy" and "generated MCP server", and says never to write "proxy" or "MCP server" alone when it is ambiguous. That sounds pedantic until you have a project that is a generator emitting a proxy and an MCP server. After this rule, the code comments, specs, README and Kiro's answers all used the same words.
- **`tech.md`**: the invariants. Generation is pure and in memory. No JAXB. XML is parsed with DTDs disabled. Dependency versions are pinned. The MCP server logs to stderr only, because stdout is the protocol channel.
- **`structure.md`**: the module layout, which module may depend on which, and the zip layout.

The biggest difference came from writing invariants as rules, not preferences. "Generation writes nothing to disk" is checkable, so every session respected it.

## A hook for the fast feedback loop

The core of the project is a model-driven converter: one piece of code that turns JSON into SOAP XML and back for _any_ WSDL. That is exactly where property-based tests shine. I used [jqwik](https://jqwik.net/) to generate random service models and random values, and to check that every value survives JSON → XML → JSON unchanged.

A Kiro hook runs the fast-tagged tests on every save in the parser, converter or generator code:

```json
{
  "name": "jqwik fast round-trip tests",
  "trigger": "PostFileSave",
  "matcher": "(core|runtime|generators)[\\\\/]src[\\\\/].*\\.java$|generators[\\\\/]src[\\\\/]main[\\\\/]resources[\\\\/]templates[\\\\/]",
  "action": {
    "type": "command",
    "command": "mvn -B -q -pl runtime,core,generators -am test -Dgroups=fast -Djqwik.tries=100",
    "timeout": 180
  }
}
```

The try count is capped at 100, and the slow end-to-end tests, which build the generated projects with Maven, never run from the hook. So every save gets feedback in seconds.

To check that the properties could actually fail, I planted a bug: string values trimmed on read. Two of the three properties failed within one or two tries and shrank to a minimal example.

## The bug that would have rounded money

The demo is a bank. The test amount is `1234567890.123456789`, which is too many digits for a `double`.

One requirement, written before the MCP server existed, said tool arguments must arrive as `BigDecimal`. A small spike checked whether the MCP Java SDK does that by default. It doesn't. With the default JSON mapper, an agent's transfer arrives as `1.2345678901234567E9`, and `0.10` becomes `0.1`.

The generated MCP server now ships a configured mapper. To prove it matters end to end, I put the default mapper back and ran the real test, which builds the generated MCP server, sends a transfer through an MCP `tools/call`, and checks the bank's ledger:

```text
asked to transfer:  1234567890.123456789
bank received:      1234567890.1234567
```

With the configured mapper, every digit arrives.

The same end-to-end test caught a subtler one in my own test code: Jackson's default `JsonNode` factory turns `2500.00` into `2.5E+3`. The proxy was right; the test's JSON parser was not. That fix became a requirement for the MCP server too.

![The MCP Server Tools tab with a copyable mcp.json entry for Kiro and each tool's input schema](https://raw.githubusercontent.com/prijal1202/wsdl-forge/main/docs/images/tab-mcp.png)
_Every SOAP operation becomes an MCP tool, with a ready-to-paste mcp.json entry for Kiro._


## Agents reviewing the code

Before launch, I ran a whole-repo production review as four parallel read-only reviews in Kiro, one each for the runtime, the parser, the generators, and the web layer with the UI. I fixed every High and Medium finding, each with a regression test written first. Two findings worth telling:

- **A 13-byte number could crash the demo.** `1E+300000000` as a transfer amount made the bank rescale a balance by 10^300000000 while holding the ledger lock. Out of memory, dead JVM. The same trick hit every generated proxy. Numbers are now bounded before any arithmetic.
- **Prompt injection through the WSDL.** WSDL documentation went verbatim into MCP tool descriptions, which an agent reads as guidance, in a demo where the agent moves money. Tool descriptions now start with the generator's own sentence and quote the WSDL text as bounded data.

## Specs versus vibe coding

I didn't spec everything. The opening animation (a blade hammered on an anvil, with a WebGL fire shader) was pure back-and-forth with Kiro: "this looks like an emoji, make it realistic", "start close on the anvil and pull back", "faster", "drop the sharpening". For something you judge by looking at it, that loop was right. A spec would have slowed it down for no gain.

![The intro: an anvil in front of a WebGL fire](https://raw.githubusercontent.com/prijal1202/wsdl-forge/main/docs/images/intro-anvil.jpg)
_The intro opens close on the anvil face and pulls back to this._

![The intro ends with the cooled blade on the anvil and the WSDLForge title](https://raw.githubusercontent.com/prijal1202/wsdl-forge/main/docs/images/intro-title.jpg)
_After three strikes the blade cools to steel and the title comes up._


For the parser and the converter, the spec was what made the work trustworthy. Every bug in this post was found by a test that existed because a requirement said what must never happen. Vibe coding tells you whether the code does what you asked. A spec also tells you what you forgot to ask.

## Three Kiro apps

I didn't stay in the IDE the whole time.

- **Kiro IDE** is where the specs, steering files and the hook live, and where most of the spec-driven work happened.
- **Kiro CLI** is where I ran chats to develop a feature and to tweak the frontend. Honestly, it was more fun to use than the chat in the IDE. Working from the terminal kept me in the flow.
- **Kiro Crew** is what I used for reviewing. It has bots, and I tried using them to review the project.

## Try it

Open [wsdlforge.onrender.com](https://wsdlforge.onrender.com) and click **Try Demo Bank Service**. The page previews everything before you download: the conversion report, the OpenAPI endpoints, the MCP tools with a copyable `mcp.json` entry for Kiro, and a sample JSON request next to the exact SOAP envelope the proxy sends, with `1234567890.123456789` intact.

![The Conversion Report tab listing each converted operation with its REST endpoint and SOAP action](https://raw.githubusercontent.com/prijal1202/wsdl-forge/main/docs/images/tab-report.png)
_The Conversion Report: what was converted, and what was skipped with its exact location in the WSDL._

![The OpenAPI 3.0 Spec tab showing an endpoint with its request and response schemas](https://raw.githubusercontent.com/prijal1202/wsdl-forge/main/docs/images/tab-openapi.png)
_The OpenAPI tab: endpoints with their schemas, or the raw YAML and JSON._


It's a free-tier deploy, so the first request after a long idle can take up to a minute.

The code, including the `.kiro` folder with every spec, steering file and hook, is at [github.com/prijal1202/wsdl-forge](https://github.com/prijal1202/wsdl-forge).

_This post was written as part of the Kiroween 2026 hackathon._
