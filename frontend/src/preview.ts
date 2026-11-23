// Reads the files the preview tabs show: preview.json (generators Requirement 8), the generated MCP
// server's tools.json and model.json. Everything here is text from the uploaded WSDL, so it is parsed
// defensively and later rendered as text only, never as HTML.
import { isRecord } from './api.ts'
import type { Artifact } from './artifacts.ts'

export interface Sample {
  operation: string
  path: string
  /** The request JSON as text: parsing it with JSON.parse would round the decimals this tab shows off. */
  request: string | null
  envelope: string | null
  soapHeaders: [string, string][]
  decimalPaths: string[]
  omitted: string | null
}

export interface McpEntry {
  serverKey: string
  jar: string
  kiroConfig: unknown
}

export interface Preview {
  service: string
  soapVersion: string
  endpoint: string | null
  sampleDecimal: string
  openapi: Record<string, unknown> | null
  mcp: McpEntry | null
  samples: Sample[]
  /** Set when the generator left the preview out to stay under the output limit. */
  truncated: string | null
}

export interface Tool {
  name: string
  operation: string
  description: string
  inputSchema: unknown
}

export interface ModelOperation {
  name: string
  soapAction: string
  input: ModelElement
  output: ModelElement
}

export interface ModelElement {
  element: string
  namespace: string
  typeId: string
  /** Fields of the element's type; null when the type is simple. */
  fields: number | null
}

export interface ModelSummary {
  operations: ModelOperation[]
  complexTypes: number
  enumTypes: number
  simpleTypes: number
  fields: number
  /** Complex types that ended up with no fields: their content was dropped. */
  emptyTypes: string[]
}

const str = (v: unknown, fallback = ''): string => (typeof v === 'string' ? v : fallback)

function file(files: Artifact[], path: string): Artifact | undefined {
  return files.find((f) => f.path === path)
}

function json(files: Artifact[], path: string): unknown {
  const f = file(files, path)
  if (!f) return undefined
  try {
    return JSON.parse(f.text)
  } catch {
    return undefined
  }
}

export function readPreview(files: Artifact[]): Preview | null {
  const raw = json(files, 'preview.json')
  if (!isRecord(raw)) return null
  const mcp = isRecord(raw.mcp)
    ? { serverKey: str(raw.mcp.serverKey), jar: str(raw.mcp.jar), kiroConfig: raw.mcp.kiroConfig }
    : null
  const samples = Array.isArray(raw.samples) ? raw.samples.filter(isRecord).map(readSample) : []
  return {
    service: str(raw.service, 'Service'),
    soapVersion: str(raw.soapVersion, '1.1'),
    endpoint: typeof raw.endpoint === 'string' ? raw.endpoint : null,
    sampleDecimal: str(raw.sampleDecimal, '1234567890.123456789'),
    openapi: isRecord(raw.openapi) ? raw.openapi : null,
    mcp,
    samples,
    truncated: typeof raw.truncated === 'string' ? raw.truncated : null,
  }
}

function readSample(s: Record<string, unknown>): Sample {
  const headers = isRecord(s.soapHeaders)
    ? Object.entries(s.soapHeaders).filter((e): e is [string, string] => typeof e[1] === 'string')
    : []
  return {
    operation: str(s.operation),
    path: str(s.path),
    request: typeof s.request === 'string' ? s.request : null,
    envelope: typeof s.envelope === 'string' ? s.envelope : null,
    soapHeaders: headers,
    decimalPaths: Array.isArray(s.decimalPaths) ? s.decimalPaths.filter((p) => typeof p === 'string') : [],
    omitted: typeof s.omitted === 'string' ? s.omitted : null,
  }
}

export function readTools(files: Artifact[]): Tool[] {
  const raw = json(files, 'mcp-server/src/main/resources/tools.json')
  if (!Array.isArray(raw)) return []
  return raw.filter(isRecord).map((t) => ({
    name: str(t.name),
    operation: str(t.operation),
    description: str(t.description),
    inputSchema: t.inputSchema,
  }))
}

export function readModel(files: Artifact[]): ModelSummary | null {
  const raw = json(files, 'model.json')
  if (!isRecord(raw) || !Array.isArray(raw.operations) || !isRecord(raw.types)) return null
  const types = raw.types
  let complexTypes = 0
  let enumTypes = 0
  let simpleTypes = 0
  let fields = 0
  const emptyTypes: string[] = []
  for (const [id, t] of Object.entries(types)) {
    if (!isRecord(t)) continue
    if (t.kind === 'COMPLEX') {
      complexTypes++
      const n = Array.isArray(t.fields) ? t.fields.length : 0
      fields += n
      if (n === 0) emptyTypes.push(id)
    } else if (Array.isArray(t.enumValues) && t.enumValues.length > 0) {
      enumTypes++
    } else if (!id.startsWith('xs:')) {
      simpleTypes++ // built-ins (xs:string, ...) are not counted as converted WSDL types
    }
  }
  const element = (ref: unknown): ModelElement => {
    const r = isRecord(ref) ? ref : {}
    const typeId = str(r.typeId)
    const t = types[typeId]
    const isComplex = isRecord(t) && t.kind === 'COMPLEX'
    return {
      element: str(r.localName),
      namespace: str(r.namespace),
      typeId,
      fields: isComplex && Array.isArray(t.fields) ? t.fields.length : null,
    }
  }
  const operations = raw.operations.filter(isRecord).map((op) => ({
    name: str(op.name),
    soapAction: str(op.soapAction),
    input: element(op.input),
    output: element(op.output),
  }))
  return { operations, complexTypes, enumTypes, simpleTypes, fields, emptyTypes }
}

/**
 * Indents the converter's one-line envelope for reading. Safe to do with a tokenizer: the
 * converter escapes every "<" in text and attribute values, so "<" only ever starts a tag.
 */
export function indentXml(xml: string): string {
  const tokens = xml.match(/<[^>]*>|[^<]+/g) ?? []
  const lines: string[] = []
  let depth = 0
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i]!
    const pad = '  '.repeat(depth)
    if (t.startsWith('<?')) {
      lines.push(t)
    } else if (t.startsWith('</')) {
      depth = Math.max(0, depth - 1)
      lines.push('  '.repeat(depth) + t)
    } else if (t.startsWith('<')) {
      const text = tokens[i + 1]
      const close = tokens[i + 2]
      if (t.endsWith('/>')) {
        lines.push(pad + t)
      } else if (text !== undefined && !text.startsWith('<') && close?.startsWith('</')) {
        lines.push(pad + t + text + close) // <amount>1234567890.123456789</amount> stays on one line
        i += 2
      } else if (text?.startsWith('</')) {
        lines.push(pad + t + text) // empty element
        i += 1
      } else {
        lines.push(pad + t)
        depth++
      }
    } else if (t.trim()) {
      lines.push(pad + t)
    }
  }
  return lines.join('\n')
}

/** Splits text around every occurrence of `needle`, for highlighting it without building HTML. */
export function splitOn(text: string, needle: string): { text: string; hit: boolean }[] {
  if (!needle) return [{ text, hit: false }]
  const parts: { text: string; hit: boolean }[] = []
  let from = 0
  for (let i = text.indexOf(needle); i !== -1; i = text.indexOf(needle, from)) {
    if (i > from) parts.push({ text: text.slice(from, i), hit: false })
    parts.push({ text: needle, hit: true })
    from = i + needle.length
  }
  if (from < text.length) parts.push({ text: text.slice(from), hit: false })
  return parts
}

export function countOf(text: string, needle: string): number {
  return needle ? splitOn(text, needle).filter((p) => p.hit).length : 0
}
