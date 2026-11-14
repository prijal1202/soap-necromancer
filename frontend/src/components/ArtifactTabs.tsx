import { useState, type KeyboardEvent } from 'react'
import { formatBytes, type ArtifactGroup } from '../artifacts.ts'

interface Props {
  groups: ArtifactGroup[]
}

/** One tab per artifact kind; inside a tab, a file list and a plain-text viewer. */
export function ArtifactTabs({ groups }: Props) {
  const [active, setActive] = useState(0)
  const [selected, setSelected] = useState<Record<string, string>>({})
  const group = groups[Math.min(active, groups.length - 1)]
  if (!group) return null

  const path = selected[group.id] ?? group.files[0]?.path
  const file = group.files.find((f) => f.path === path) ?? group.files[0]

  function onTabKey(e: KeyboardEvent) {
    const step = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0
    if (step === 0) return
    e.preventDefault()
    const next = (active + step + groups.length) % groups.length
    setActive(next)
    document.getElementById(`tab-${groups[next]!.id}`)?.focus()
  }

  return (
    <div className="artifacts">
      <div role="tablist" aria-label="Generated artifacts" className="tabs" onKeyDown={onTabKey}>
        {groups.map((g, i) => (
          <button
            key={g.id}
            id={`tab-${g.id}`}
            role="tab"
            type="button"
            aria-selected={i === active}
            aria-controls={`panel-${g.id}`}
            tabIndex={i === active ? 0 : -1}
            onClick={() => setActive(i)}
          >
            {g.label}
            {g.files.length > 1 && <span className="count">{g.files.length}</span>}
          </button>
        ))}
      </div>

      <div role="tabpanel" id={`panel-${group.id}`} aria-labelledby={`tab-${group.id}`} className="tabpanel">
        <p className="summary">{group.summary}</p>
        <div className={group.files.length > 1 ? 'browser' : 'browser single'}>
          {group.files.length > 1 && (
            <ul className="files" aria-label={`${group.label} files`}>
              {group.files.map((f) => {
                const { name, folder } = splitPath(f.path)
                return (
                  <li key={f.path}>
                    <button
                      type="button"
                      aria-current={f.path === file?.path}
                      title={f.path}
                      onClick={() => setSelected({ ...selected, [group.id]: f.path })}
                    >
                      <span className="name">{name}</span>
                      {folder && <span className="folder">{folder}</span>}
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
          {file && <Viewer path={file.path} text={file.text} bytes={file.bytes} />}
        </div>
      </div>
    </div>
  )
}

function Viewer({ path, text, bytes }: { path: string; text: string; bytes: number }) {
  const [copied, setCopied] = useState<string | null>(null)
  return (
    <figure className="viewer">
      <figcaption>
        <code>{path}</code>
        <span className="meta">
          {formatBytes(bytes)} · {text.split('\n').length} lines
        </span>
        <button
          type="button"
          className="secondary small"
          onClick={() => {
            void navigator.clipboard?.writeText(text).then(() => setCopied(path))
          }}
        >
          {copied === path ? 'Copied' : 'Copy'}
        </button>
      </figcaption>
      {/* Plain text on purpose: README and Java comments carry text from the uploaded WSDL. */}
      <pre>
        <code>{text}</code>
      </pre>
    </figure>
  )
}

/**
 * "proxy/src/main/java/com/acme/bank/GetBalanceController.java" -> name "GetBalanceController.java",
 * folder "…/acme/bank". The full path stays in the tooltip and the viewer header.
 */
function splitPath(path: string): { name: string; folder: string } {
  const parts = path.split('/')
  const name = parts.pop() ?? path
  const sourceRoot = parts.findIndex((p) => p === 'java' || p === 'resources')
  const where = sourceRoot >= 0 ? parts.slice(sourceRoot + 1) : parts.slice(1)
  const folder = where.length > 2 ? `…/${where.slice(-2).join('/')}` : where.join('/')
  return { name, folder }
}
