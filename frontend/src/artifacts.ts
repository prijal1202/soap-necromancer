// Reads the generated zip in the browser and sorts its files into the UI's tabs.
// Layout (structure.md): model.json, openapi.yaml, generator-report.json, README.md, proxy/, mcp-server/.
import { unzipSync } from 'fflate'
import { isRecord, parseWarnings, type Warning } from './api.ts'

export interface Artifact {
  path: string
  text: string
  bytes: number
  lines: number
}

export interface ArtifactGroup {
  id: string
  label: string
  summary: string
  files: Artifact[]
}

export interface Report {
  service: string
  operations: string[]
  warnings: Warning[]
}

export function readZip(zip: ArrayBuffer): Artifact[] {
  const entries = unzipSync(new Uint8Array(zip))
  const utf8 = new TextDecoder('utf-8')
  return Object.entries(entries)
    .filter(([path]) => !path.endsWith('/'))
    .map(([path, data]) => {
      const text = utf8.decode(data)
      return { path, text, bytes: data.length, lines: countLines(text) }
    })
    .sort((a, b) => a.path.localeCompare(b.path))
}

const TEST_ROOT = 'proxy/src/test/'

const GROUPS: { id: string; label: string; summary: string; matches: (path: string) => boolean }[] = [
  {
    id: 'readme',
    label: 'README',
    summary: 'How to run the generated proxy and register the generated MCP server.',
    matches: (p) => p === 'README.md',
  },
  {
    id: 'openapi',
    label: 'OpenAPI',
    summary: 'OpenAPI 3.0 description of the generated proxy.',
    matches: (p) => p === 'openapi.yaml',
  },
  {
    id: 'proxy',
    label: 'Generated proxy',
    summary: 'Spring Boot 3 REST service: JSON in, SOAP 1.1 out. Includes the converter runtime.',
    matches: (p) => p.startsWith('proxy/') && !p.startsWith(TEST_ROOT),
  },
  {
    id: 'tests',
    label: 'Tests',
    summary: 'REST Assured contract tests per operation and jqwik JSON<->XML round-trip properties.',
    matches: (p) => p.startsWith(TEST_ROOT),
  },
  {
    id: 'mcp',
    label: 'Generated MCP server',
    summary: 'stdio MCP server: one tool per SOAP operation, forwarding to the generated proxy.',
    matches: (p) => p.startsWith('mcp-server/'),
  },
  {
    id: 'model',
    label: 'Model & report',
    summary: 'The canonical model the generator built, and its warnings report.',
    matches: () => true, // model.json, generator-report.json, and anything a newer generator adds
  },
]

/** The converter runtime copied into every generated proxy: shared code, so list it after the service's own. */
const COPIED_RUNTIME = '/dev/soapnecromancer/runtime/'

export function groupArtifacts(files: Artifact[]): ArtifactGroup[] {
  const buckets = new Map<string, Artifact[]>(GROUPS.map((g) => [g.id, []]))
  const ordered = [...files].sort(
    (a, b) => Number(a.path.includes(COPIED_RUNTIME)) - Number(b.path.includes(COPIED_RUNTIME)),
  )
  for (const file of ordered) {
    const group = GROUPS.find((g) => g.matches(file.path))!
    buckets.get(group.id)!.push(file)
  }
  return GROUPS.map(({ id, label, summary }) => ({ id, label, summary, files: buckets.get(id)! })).filter(
    (g) => g.files.length > 0,
  )
}

export function readReport(files: Artifact[]): Report | null {
  const file = files.find((f) => f.path === 'generator-report.json')
  if (!file) return null
  try {
    const raw: unknown = JSON.parse(file.text)
    if (!isRecord(raw)) return null
    return {
      service: typeof raw.service === 'string' ? raw.service : 'Service',
      operations: Array.isArray(raw.operations) ? raw.operations.filter((o) => typeof o === 'string') : [],
      warnings: parseWarnings(raw.warnings),
    }
  } catch {
    return null
  }
}

/** Counted once per file instead of splitting the whole text on every render. */
function countLines(text: string): number {
  let n = 1
  for (let i = text.indexOf('\n'); i !== -1; i = text.indexOf('\n', i + 1)) n++
  return n
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
