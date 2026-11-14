import { useEffect, useRef, useState } from 'react'
import { generate, NetworkError, SAMPLES, sampleWsdl, type Problem } from './api.ts'
import { formatBytes, groupArtifacts, readReport, readZip, type ArtifactGroup, type Report } from './artifacts.ts'
import { ArtifactTabs } from './components/ArtifactTabs.tsx'
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

  async function resurrect(load: () => Promise<File>, label: string) {
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
            },
          })
          return
        }
        const retryable = outcome.problem.status === 503 || outcome.problem.status === 429
        if (retryable && attempt < MAX_BUSY_RETRIES) {
          const wait = Math.min(outcome.retryAfterSeconds ?? 5, MAX_RETRY_WAIT_SECONDS)
          setState({ phase: 'working', source: label, note: `the generator is busy, retrying in ${wait} s…` })
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
      <header className="hero">
        <h1>
          <img src="/favicon.svg" alt="" width={44} height={44} />
          SOAP Necromancer
        </h1>
        <p className="tagline">Resurrect legacy SOAP services for REST clients and AI agents.</p>
        <p className="lede">
          Upload a WSDL. Get back one zip with an OpenAPI 3.0 spec, a <strong>generated proxy</strong> (Spring Boot,
          JSON in, SOAP 1.1 or 1.2 out), a <strong>generated MCP server</strong> that gives agents like Kiro one tool per
          operation, and the tests that prove the <span className="nowrap">JSON&lt;-&gt;XML</span> mapping is lossless. Nothing is stored on the server.
        </p>
      </header>

      <main>
        <UploadPanel
          busy={busy}
          onFile={(file) => void resurrect(async () => file, file.name)}
          onSample={(version) =>
            void resurrect(() => sampleWsdl(version), `${SAMPLES[version].filename} (sample)`)
          }
        />

        <section className="panel results" aria-labelledby="results-title" aria-busy={busy}>
          <h2 id="results-title">2. Inspect and download</h2>
          {/* Only this short line is announced; the file viewer below must not be read out on every change. */}
          <p className="sr-only" role="status">
            {statusText(state)}
          </p>
          {state.phase === 'idle' && (
            <p className="placeholder">Nothing summoned yet. The generated files will appear here.</p>
          )}
          {state.phase === 'working' && (
            <p className="working">
              <span className="spinner" aria-hidden="true" />
              <span>
                <strong>{state.source}</strong>: {state.note}
              </span>
            </p>
          )}
          {state.phase === 'failed' && <Failure source={state.source} problem={state.problem} />}
          {state.phase === 'done' && <ResultView result={state.result} />}
        </section>
      </main>

      <footer>
        <p>
          Supported: SOAP 1.1 and 1.2 over HTTP, <code>document/literal</code>, the core XSD types. rpc/encoded,
          WS-Security and WS-Addressing are reported as warnings. Uploads up to 2 MB.
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
  const { report } = result
  const operations = report?.operations.length ?? 0
  return (
    <div className="result">
      <div className="result-head">
        <div>
          <h3>{report?.service ?? 'Generated service'}</h3>
          <p className="meta">
            {operations} operation{operations === 1 ? '' : 's'}
            {report && report.operations.length > 0 && <> ({report.operations.join(', ')})</>} · {result.fileCount}{' '}
            files · {formatBytes(result.zipBytes)} zip
          </p>
        </div>
        <a className="button primary" href={result.url} download={result.filename}>
          Download {result.filename}
        </a>
      </div>
      <WarningsPanel warnings={report?.warnings ?? []} mode="report" />
      <ArtifactTabs key={result.id} groups={result.groups} />
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
