// Client for the generator API (README "Generator API"). Same origin: the jar serves this UI.

/** One unsupported-feature warning. Same shape in generator-report.json and in a 422 body. */
export interface Warning {
  code: string
  message: string
  location: string
}

/** RFC 7807 problem detail. A 422 adds `warnings`. */
export interface Problem {
  status: number
  title: string
  detail: string
  warnings: Warning[]
}

export type GenerateOutcome =
  | { kind: 'zip'; zip: ArrayBuffer; filename: string }
  | { kind: 'problem'; problem: Problem; retryAfterSeconds: number | null }

/** Thrown when the request never got an HTTP answer (offline, DNS, a sleeping host that timed out). */
export class NetworkError extends Error {}

export async function generate(wsdl: File): Promise<GenerateOutcome> {
  const body = new FormData()
  body.append('wsdl', wsdl, wsdl.name)
  const res = await send('/api/generate', { method: 'POST', body })
  const type = res.headers.get('Content-Type') ?? ''
  if (res.ok && type.includes('zip')) {
    return { kind: 'zip', zip: await res.arrayBuffer(), filename: filenameFrom(res.headers.get('Content-Disposition')) }
  }
  return {
    kind: 'problem',
    problem: await readProblem(res),
    retryAfterSeconds: res.status === 503 ? retryAfter(res.headers.get('Retry-After')) : null,
  }
}

/** The bank WSDL, with its SOAP address pointing at this deployment's mock bank. */
export async function sampleWsdl(): Promise<File> {
  const res = await send('/api/sample-wsdl', { headers: { Accept: 'text/xml' } })
  if (!res.ok) {
    throw new NetworkError(`The sample WSDL could not be loaded (HTTP ${res.status}).`)
  }
  return new File([await res.text()], 'bank.wsdl', { type: 'text/xml' })
}

async function send(url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init)
  } catch {
    throw new NetworkError('The generator could not be reached.')
  }
}

async function readProblem(res: Response): Promise<Problem> {
  const fallback: Problem = {
    status: res.status,
    title: res.ok ? 'Unexpected response' : `HTTP ${res.status}`,
    detail: res.ok
      ? 'The generator answered, but not with a zip.'
      : 'The generator answered with an error and no details.',
    warnings: [],
  }
  if (!(res.headers.get('Content-Type') ?? '').includes('json')) return fallback
  let raw: unknown
  try {
    raw = await res.json()
  } catch {
    return fallback
  }
  if (!isRecord(raw)) return fallback
  return {
    status: typeof raw.status === 'number' ? raw.status : res.status,
    title: typeof raw.title === 'string' ? raw.title : fallback.title,
    detail: typeof raw.detail === 'string' ? raw.detail : fallback.detail,
    warnings: parseWarnings(raw.warnings),
  }
}

/** Keeps only well-formed entries, so a malformed body can never break rendering. */
export function parseWarnings(value: unknown): Warning[] {
  if (!Array.isArray(value)) return []
  return value.filter(isRecord).map((w) => ({
    code: typeof w.code === 'string' ? w.code : 'UNKNOWN',
    message: typeof w.message === 'string' ? w.message : '',
    location: typeof w.location === 'string' ? w.location : '',
  }))
}

function filenameFrom(disposition: string | null): string {
  const match = disposition?.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i)
  const name = match?.[1] ? decodeURIComponent(match[1]) : ''
  // The server builds the name from the service name; still strip anything path-like.
  const safe = name.replace(/[\\/:*?"<>|]/g, '_').trim()
  return safe.endsWith('.zip') ? safe : 'resurrected.zip'
}

function retryAfter(header: string | null): number | null {
  const seconds = Number(header)
  return Number.isFinite(seconds) && seconds > 0 ? seconds : null
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
