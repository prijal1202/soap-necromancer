import { useMemo, useState } from 'react'
import { isRecord } from '../api.ts'
import { CodeBlock, Filter, Lazy, matches } from './common.tsx'

type Json = Record<string, unknown>

interface Props {
  /** The OpenAPI document from preview.json; null for a zip without one. */
  openapi: Json | null
  yaml: string | null
}

interface Endpoint {
  path: string
  method: string
  operationId: string
  summary: string
  description: string
  request: unknown
  responses: { code: string; description: string; mediaType: string; schema: unknown }[]
}

const REF = '#/components/schemas/'
type Mode = 'rendered' | 'yaml' | 'json'

export function OpenApiView({ openapi, yaml }: Props) {
  const [mode, setMode] = useState<Mode>(openapi ? 'rendered' : 'yaml')
  const endpoints = useMemo(() => (openapi ? readEndpoints(openapi) : []), [openapi])
  const schemas = useMemo(() => {
    const c = openapi && isRecord(openapi.components) ? openapi.components : null
    return c && isRecord(c.schemas) ? c.schemas : {}
  }, [openapi])
  const jsonText = useMemo(() => (openapi ? JSON.stringify(openapi, null, 2) : ''), [openapi])
  const info = openapi && isRecord(openapi.info) ? openapi.info : {}

  return (
    <div className="openapi">
      <div className="toolbar">
        <p className="muted">
          OpenAPI {typeof openapi?.openapi === 'string' ? openapi.openapi : '3.0'} for the generated proxy ·{' '}
          {endpoints.length} endpoint{endpoints.length === 1 ? '' : 's'} · {Object.keys(schemas).length} schemas
        </p>
        <div className="segmented" role="group" aria-label="OpenAPI view">
          {(['rendered', 'yaml', 'json'] as const).map((m) => (
            <button
              key={m}
              type="button"
              className="secondary small"
              aria-pressed={mode === m}
              disabled={m !== 'yaml' && !openapi}
              onClick={() => setMode(m)}
            >
              {m === 'rendered' ? 'Endpoints' : m === 'yaml' ? 'Raw YAML' : 'Raw JSON'}
            </button>
          ))}
        </div>
      </div>
      {mode === 'yaml' && yaml !== null && <CodeBlock title={<code>openapi.yaml</code>} text={yaml} />}
      {mode === 'json' && <CodeBlock title="openapi.json (same document as openapi.yaml)" text={jsonText} />}
      {mode === 'rendered' && (
        <>
          {typeof info.description === 'string' && <p className="summary">{info.description}</p>}
          <Endpoints endpoints={endpoints} schemas={schemas} />
        </>
      )}
    </div>
  )
}

function Endpoints({ endpoints, schemas }: { endpoints: Endpoint[]; schemas: Json }) {
  const [selected, setSelected] = useState(0)
  const [filter, setFilter] = useState('')
  const shown = endpoints.map((e, i) => ({ e, i })).filter(({ e }) => matches(filter, e.path, e.summary))
  const current = endpoints[selected] ?? endpoints[0]
  if (!current) return <p className="muted">No endpoints.</p>
  return (
    <div className="browser">
      <div>
        {endpoints.length > 8 && <Filter value={filter} onChange={setFilter} label="Filter endpoints" />}
        <ul className="files" aria-label="Endpoints">
          {shown.map(({ e, i }) => (
            <li key={e.path + e.method}>
              <button type="button" aria-current={e === current} onClick={() => setSelected(i)}>
                <span className="name">
                  <span className="method">{e.method.toUpperCase()}</span> {e.path}
                </span>
                {e.summary && <span className="folder">{e.summary}</span>}
              </button>
            </li>
          ))}
        </ul>
      </div>
      <article className="endpoint" aria-label={`${current.method.toUpperCase()} ${current.path}`}>
        <h4>
          <span className="method">{current.method.toUpperCase()}</span> <code>{current.path}</code>
        </h4>
        {current.summary && <p>{current.summary}</p>}
        <p className="muted">
          {current.description} operationId <code>{current.operationId}</code>
        </p>
        <h5>Request body · application/json</h5>
        <SchemaView schema={current.request} schemas={schemas} />
        <h5>Responses</h5>
        {current.responses.map((r) => (
          <div key={r.code} className="response">
            <p>
              <span className={`status s${r.code[0]}`}>{r.code}</span> {r.description}{' '}
              <span className="muted">{r.mediaType}</span>
            </p>
            {r.code === '200' ? (
              <SchemaView schema={r.schema} schemas={schemas} />
            ) : (
              <Lazy summary={<span className="muted">Problem body</span>}>
                {() => <SchemaView schema={r.schema} schemas={schemas} />}
              </Lazy>
            )}
          </div>
        ))}
      </article>
    </div>
  )
}

function readEndpoints(doc: Json): Endpoint[] {
  const paths = isRecord(doc.paths) ? doc.paths : {}
  const out: Endpoint[] = []
  for (const [path, item] of Object.entries(paths)) {
    if (!isRecord(item)) continue
    for (const [method, op] of Object.entries(item)) {
      if (!isRecord(op)) continue
      const content = (v: unknown): [string, unknown] => {
        const c = isRecord(v) && isRecord(v.content) ? v.content : {}
        const [mediaType, media] = Object.entries(c)[0] ?? ['', undefined]
        return [mediaType, isRecord(media) ? media.schema : undefined]
      }
      const responses = isRecord(op.responses) ? op.responses : {}
      out.push({
        path,
        method,
        operationId: typeof op.operationId === 'string' ? op.operationId : '',
        summary: typeof op.summary === 'string' ? op.summary : '',
        description: typeof op.description === 'string' ? op.description : '',
        request: content(op.requestBody)[1],
        responses: Object.entries(responses).map(([code, r]) => {
          const [mediaType, schema] = content(r)
          return { code, description: isRecord(r) && typeof r.description === 'string' ? r.description : '', mediaType, schema }
        }),
      })
    }
  }
  return out
}

// ---------------------------------------------------------------- schema tree

function refName(s: unknown): string | null {
  return isRecord(s) && typeof s.$ref === 'string' && s.$ref.startsWith(REF) ? s.$ref.slice(REF.length) : null
}

function typeLabel(s: unknown): string {
  if (!isRecord(s)) return 'any'
  const ref = refName(s)
  if (ref) return ref
  if (s.type === 'array') {
    const bounds = [s.minItems !== undefined ? `min ${String(s.minItems)}` : '', s.maxItems !== undefined ? `max ${String(s.maxItems)}` : '']
      .filter(Boolean)
      .join(', ')
    return `array of ${typeLabel(s.items)}${bounds ? ` (${bounds})` : ''}`
  }
  const type = typeof s.type === 'string' ? s.type : 'any'
  return typeof s.format === 'string' ? `${type} (${s.format})` : type
}

/** The object schema a field leads to, following one $ref and array items. */
function target(s: unknown, schemas: Json): { name: string | null; schema: Json } | null {
  if (!isRecord(s)) return null
  if (s.type === 'array') return target(s.items, schemas)
  const name = refName(s)
  const resolved = name ? schemas[name] : s
  return isRecord(resolved) ? { name, schema: resolved } : null
}

function SchemaView({ schema, schemas }: { schema: unknown; schemas: Json }) {
  const t = target(schema, schemas)
  if (!t) return <p className="muted">No schema.</p>
  return (
    <div className="schema-root">
      {t.name && (
        <p className="muted">
          <code>{t.name}</code>
        </p>
      )}
      <SchemaBody schema={t.schema} schemas={schemas} seen={t.name ? [t.name] : []} />
    </div>
  )
}

function SchemaBody({ schema, schemas, seen }: { schema: Json; schemas: Json; seen: string[] }) {
  if (Array.isArray(schema.enum)) {
    return <EnumValues values={schema.enum} />
  }
  const props = isRecord(schema.properties) ? Object.entries(schema.properties) : []
  if (props.length === 0) {
    return <p className="muted">{schema.type === 'object' ? 'No fields ({})' : typeLabel(schema)}</p>
  }
  const required = new Set(Array.isArray(schema.required) ? schema.required.filter((r) => typeof r === 'string') : [])
  return (
    <ul className="schema">
      {props.map(([name, field]) => {
        const next = target(field, schemas)
        const recursive = next?.name ? seen.includes(next.name) : false
        const nested = next && !recursive && (isRecord(next.schema.properties) || Array.isArray(next.schema.enum))
        const description = isRecord(field) && typeof field.description === 'string' ? field.description : ''
        return (
          <li key={name}>
            <code className="field">{name}</code> <span className="type">{typeLabel(field)}</span>
            {required.has(name) && <span className="badge">required</span>}
            {recursive && <span className="badge muted-badge">recursive</span>}
            {isRecord(field) && Array.isArray(field.enum) && <EnumValues values={field.enum} />}
            {description && <span className="muted"> {description}</span>}
            {nested && next && (
              <Lazy summary={<span className="muted">fields of {next.name ?? 'object'}</span>}>
                {() => <SchemaBody schema={next.schema} schemas={schemas} seen={next.name ? [...seen, next.name] : seen} />}
              </Lazy>
            )}
          </li>
        )
      })}
    </ul>
  )
}

function EnumValues({ values }: { values: unknown[] }) {
  return (
    <span className="enum">
      {' '}
      one of{' '}
      {values.slice(0, 20).map((v, i) => (
        <span key={i}>
          {i > 0 && ' | '}
          <code>{String(v)}</code>
        </span>
      ))}
      {values.length > 20 && <span className="muted"> and {values.length - 20} more</span>}
    </span>
  )
}
