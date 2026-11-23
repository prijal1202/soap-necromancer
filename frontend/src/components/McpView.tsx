import { useMemo, useState } from 'react'
import type { McpEntry, Tool } from '../preview.ts'
import { CodeBlock, CopyButton, Filter, Lazy, matches } from './common.tsx'

interface Props {
  tools: Tool[]
  mcp: McpEntry | null
}

/** The generated MCP server as Kiro sees it: the mcp.json entry and every tool with its input schema. */
export function McpView({ tools, mcp }: Props) {
  const [filter, setFilter] = useState('')
  const snippet = useMemo(() => (mcp ? JSON.stringify(mcp.kiroConfig, null, 2) + '\n' : ''), [mcp])
  const shown = tools.filter((t) => matches(filter, t.name, t.operation, t.description))

  return (
    <div className="mcp">
      {mcp ? (
        <section aria-labelledby="mcp-config-title">
          <h3 id="mcp-config-title" className="section-title">
            Add it to Kiro
          </h3>
          <p className="muted">
            Build it with <code>mvn package</code> in <code>mcp-server/</code>, then put this in{' '}
            <code>.kiro/settings/mcp.json</code> (workspace) or <code>~/.kiro/settings/mcp.json</code> (user), with the
            absolute path to <code>{mcp.jar}</code>. Start the generated proxy first: <code>PROXY_URL</code> is where it
            listens. Tool calls go to the generated proxy over REST; the MCP server never talks SOAP itself.
          </p>
          <CodeBlock title={<code>mcp.json</code>} text={snippet} />
        </section>
      ) : (
        <p className="muted">This zip has no preview data; the mcp.json snippet is in mcp-server/README.md.</p>
      )}

      <section aria-labelledby="mcp-tools-title">
        <h3 id="mcp-tools-title" className="section-title">
          {tools.length} tool{tools.length === 1 ? '' : 's'}, one per SOAP operation
        </h3>
        <p className="muted small-text">
          Descriptions quote the WSDL's documentation as data, after the generator's own sentence, so an agent does
          not mistake it for instructions. Decimal inputs are JSON numbers that keep every digit.
        </p>
        {tools.length > 8 && <Filter value={filter} onChange={setFilter} label="Filter tools" />}
        <ul className="tools">
          {shown.map((t, i) => (
            <ToolCard key={t.name} tool={t} open={i === 0 && !filter} />
          ))}
        </ul>
      </section>
    </div>
  )
}

function ToolCard({ tool, open }: { tool: Tool; open: boolean }) {
  const schema = useMemo(() => JSON.stringify(tool.inputSchema, null, 2) ?? 'null', [tool.inputSchema])
  return (
    <li className="tool">
      <div className="tool-head">
        <code className="tool-name">{tool.name}</code>
        <span className="muted">
          → <code>POST /{tool.operation}</code> on the generated proxy
        </span>
      </div>
      <p className="tool-description">{tool.description}</p>
      <Lazy
        open={open}
        summary={
          <span>
            Input schema <span className="muted">(JSON Schema 2020-12)</span>
          </span>
        }
      >
        {() => (
          <figure className="viewer code-block">
            <figcaption>
              <span className="code-title">inputSchema</span>
              <CopyButton text={schema} />
            </figcaption>
            <pre>
              <code>{schema}</code>
            </pre>
          </figure>
        )}
      </Lazy>
    </li>
  )
}
