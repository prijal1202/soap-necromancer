import { useEffect, useRef, useState } from 'react'
import { generate, NetworkError, SAMPLES, sampleWsdl, type Problem } from './api.ts'
import {
  formatBytes,
  groupArtifacts,
  readHealth,
  readReport,
  readZip,
  type ArtifactGroup,
  type Health,
  type Report,
} from './artifacts.ts'
import { ArtifactTabs } from './components/ArtifactTabs.tsx'
import { ForgeAnimation } from './components/ForgeAnimation.tsx'
import { UploadPanel } from './components/UploadPanel.tsx'
import { WarningsPanel } from './components/WarningsPanel.tsx'

const REPO = 'https://github.com/prijal1202/soap-necromancer'
/** A 503 means every generation slot stayed busy for 5 s. Retry a couple of times, then report it. */
const MAX_BUSY_RETRIES = 2
const MAX_RETRY_WAIT_SECONDS = 15

interface Result {
  id: number
  filename: string
  url: string
  zipBytes: number
  fileCount: number
  groups: ArtifactGroup[]
  report: Report | null
  health: Health | null
}

type State =
  | { phase: 'idle' }
  | { phase: 'working'; source: string; note: string }
  | { phase: 'done'; result: Result }
  | { phase: 'failed'; source: string; problem: Problem }

export default function App() {
  const [state, setState] = useState<State>({ phase: 'idle' })
  const runId = useRef(0)
  const zipUrl = useRef<string | null>(null)

  useEffect(() => () => revokeZip(), [])

  function revokeZip() {
    if (zipUrl.current) URL.revokeObjectURL(zipUrl.current)
    zipUrl.current = null
  }

  async function forge(load: () => Promise<File>, label: string) {
    const id = ++runId.current
    const stale = () => runId.current !== id
    setState({ phase: 'working', source: label, note: 'parsing the WSDL and generating every artifact…' })
    try {
      const wsdl = await load()
      for (let attempt = 0; ; attempt++) {
        const outcome = await generate(wsdl)
        if (stale()) return
        if (outcome.kind === 'zip') {
          revokeZip()
          const files = readZip(outcome.zip)
          zipUrl.current = URL.createObjectURL(new Blob([outcome.zip], { type: 'application/zip' }))
          setState({
            phase: 'done',
            result: {
              id,
              filename: outcome.filename,
              url: zipUrl.current,
              zipBytes: outcome.zip.byteLength,
              fileCount: files.length,
              groups: groupArtifacts(files),
              report: readReport(files),
              health: readHealth(files),
            },
          })
          return
        }
        const retryable = outcome.problem.status === 503 || outcome.problem.status === 429
        if (retryable && attempt < MAX_BUSY_RETRIES) {
          const wait = Math.min(outcome.retryAfterSeconds ?? 5, MAX_RETRY_WAIT_SECONDS)
          setState({ phase: 'working', source: label, note: `every anvil is busy, retrying in ${wait} s…` })
          // Jitter, so visitors told the same Retry-After do not all come back in the same instant.
          await new Promise((resolve) => setTimeout(resolve, wait * 1000 + Math.random() * 1000))
          if (stale()) return
          continue
        }
        fail(label, outcome.problem)
        return
      }
    } catch (e) {
      if (!stale()) fail(label, clientProblem(e))
    }
  }

  /** A failure replaces the previous result, so its download URL is released too. */
  function fail(source: string, problem: Problem) {
    revokeZip()
    setState({ phase: 'failed', source, problem })
  }

  const busy = state.phase === 'working'

  return (
    <>
      <div className="embers" aria-hidden="true">
        {Array.from({ length: 14 }, (_, i) => (
          <span key={i} style={{ ['--i' as string]: i }} />
        ))}
      </div>
      <header className="hero">
        <h1>
          <img src="/favicon.svg" alt="" width={52} height={52} />
          <span>
            WSDL<span className="forge-word">Forge</span>
          </span>
        </h1>
        <p className="tagline">Old SOAP goes in the fire. REST, OpenAPI and MCP come out the other side.</p>
        <p className="lede">
          Toss in a crusty WSDL. We heat it, hammer it and quench it into one zip: an OpenAPI 3.0 spec, a{' '}
          <strong>generated proxy</strong> (Spring Boot, JSON in, SOAP 1.1 or 1.2 out), a{' '}
          <strong>generated MCP server</strong> that hands agents like Kiro one tool per operation, and the tests that
          prove the <span className="nowrap">JSON&lt;-&gt;XML</span> mapping loses nothing. Nothing is stored on the
          server.
        </p>
      </header>

      <main>
        <UploadPanel
          busy={busy}
          onFile={(file) => void forge(async () => file, file.name)}
          onSample={(version) => void forge(() => sampleWsdl(version), `${SAMPLES[version].filename} (sample)`)}
        />

        <section className="panel results" aria-labelledby="results-title" aria-busy={busy}>
          <h2 id="results-title">
            <span className="step">2</span> Inspect the casting and carry it home
          </h2>
          {/* Only this short line is announced; the file viewer below must not be read out on every change. */}
          <p className="sr-only" role="status">
            {statusText(state)}
          </p>
          {state.phase === 'idle' && (
            <p className="placeholder">The forge is cold. Your freshly forged files will cool down right here.</p>
          )}
          {state.phase === 'working' && (
            <div>
              <ForgeAnimation note={`${state.source}: ${state.note}`} />
            </div>
          )}
          {state.phase === 'failed' && <Failure source={state.source} problem={state.problem} />}
          {state.phase === 'done' && <ResultView result={state.result} />}
        </section>
      </main>

      <footer>
        <p>
          Metals we work: SOAP 1.1 and 1.2 over HTTP, <code>document/literal</code>, the core XSD types and type
          extension. rpc/encoded, WS-Security and WS-Addressing are reported as warnings. Uploads up to 2 MB.
        </p>
        <p>
          The sample bank is shared by every visitor and resets to its seeded accounts every 30 minutes.{' '}
          <a href={REPO}>Source on GitHub</a> · <a href={`${REPO}#generator-api`}>API</a>
        </p>
      </footer>
    </>
  )
}

function ResultView({ result }: { result: Result }) {
  const { report, health } = result
  const operations = report?.operations.length ?? 0
  return (
    <div className="result">
      <div className="result-head">
        <div>
          <p className="forged-badge">
            <span aria-hidden="true">✨</span> Freshly forged
          </p>
          <h3>{report?.service ?? 'Generated service'}</h3>
          <p className="meta">
            {operations} operation{operations === 1 ? '' : 's'}
            {report && report.operations.length > 0 && <> ({report.operations.join(', ')})</>} · {result.fileCount}{' '}
            files · {formatBytes(result.zipBytes)} zip
          </p>
        </div>
        <a className="button primary forge-button" href={result.url} download={result.filename}>
          <span aria-hidden="true">⬇</span> Download {result.filename}
        </a>
      </div>
      {health && health.hollow > 0 && <HollowWarning health={health} />}
      <WarningsPanel warnings={report?.warnings ?? []} mode="report" />
      <ArtifactTabs key={result.id} groups={result.groups} />
    </div>
  )
}

/**
 * A zip that generated fine can still be hollow: operations whose request and response types lost every field.
 * Say so plainly instead of leaving it to a long warnings table.
 */
function HollowWarning({ health }: { health: Health }) {
  const all = health.hollow === health.operations
  return (
    <div className="hollow" role="note">
      <h3>
        {all ? 'Hollow casting' : 'Partly hollow casting'}: {health.hollow} of {health.operations} operation
        {health.operations === 1 ? '' : 's'} {health.hollow === 1 ? 'has' : 'have'} no fields
      </h3>
      <p>
        Their requests and responses came out empty, so the generated API{all ? '' : ' for those operations'} sends
        and returns <code>{'{}'}</code>. The warnings below say why. Usually it is a schema feature outside the
        supported subset, or types defined in a separate XSD file that the forge does not fetch.
      </p>
    </div>
  )
}

function statusText(state: State): string {
  switch (state.phase) {
    case 'idle':
      return ''
    case 'working':
      return `${state.source}: ${state.note}`
    case 'done':
      return `Generated ${state.result.fileCount} files for ${state.result.report?.service ?? 'the service'}.`
    case 'failed':
      return `${state.problem.title}. ${state.problem.detail}`
  }
}

function Failure({ source, problem }: { source: string; problem: Problem }) {
  return (
    <div className="failure">
      <h3>{problem.title}</h3>
      <p>{problem.detail}</p>
      <p className="meta">
        {source}
        {problem.status > 0 && <> · HTTP {problem.status}</>}
      </p>
      <WarningsPanel warnings={problem.warnings} mode="rejected" />
    </div>
  )
}

/** Failures that happened in the browser, so there is no problem+json to show. */
function clientProblem(e: unknown): Problem {
  if (e instanceof NetworkError) {
    return {
      status: 0,
      title: 'Could not reach the generator',
      detail: `${e.message} If the service was asleep it can take up to a minute to wake up. Try again.`,
      warnings: [],
    }
  }
  return {
    status: 0,
    title: 'Could not read the generated zip',
    detail: e instanceof Error ? e.message : String(e),
    warnings: [],
  }
}
