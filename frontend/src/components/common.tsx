import { useState, type ReactNode } from 'react'
import { splitOn } from '../preview.ts'

/** Copies `text`; says whether it worked (the clipboard is refused without focus or permission). */
export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [state, setState] = useState<{ text: string; ok: boolean } | null>(null)
  const shown = state?.text !== text ? label : state.ok ? 'Copied' : 'Copy failed'
  async function copy() {
    try {
      await navigator.clipboard.writeText(text)
      setState({ text, ok: true })
    } catch {
      setState({ text, ok: false })
    }
  }
  return (
    <button type="button" className="secondary small" onClick={() => void copy()}>
      {shown}
    </button>
  )
}

/**
 * A <details> whose content is only rendered once opened: a large service has thousands of schema
 * nodes, and building them all up front would freeze the page.
 */
export function Lazy({ summary, children, open = false }: { summary: ReactNode; children: () => ReactNode; open?: boolean }) {
  const [opened, setOpened] = useState(open)
  return (
    <details open={open} onToggle={(e) => e.currentTarget.open && setOpened(true)}>
      <summary>{summary}</summary>
      {opened && children()}
    </details>
  )
}

/** A titled code block with a copy button. Plain text: the content quotes the uploaded WSDL. */
export function CodeBlock({
  title,
  text,
  highlight,
  actions,
}: {
  title: ReactNode
  text: string
  highlight?: string
  actions?: ReactNode
}) {
  return (
    <figure className="viewer code-block">
      <figcaption>
        <span className="code-title">{title}</span>
        {actions}
        <CopyButton text={text} />
      </figcaption>
      <pre>
        <code>
          {highlight
            ? splitOn(text, highlight).map((p, i) => (p.hit ? <mark key={i}>{p.text}</mark> : p.text))
            : text}
        </code>
      </pre>
    </figure>
  )
}

/** "Filter" box shown once a list gets long enough to need one. */
export function Filter({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  return (
    <input
      type="search"
      className="filter"
      placeholder={label}
      aria-label={label}
      value={value}
      onChange={(e) => onChange(e.target.value)}
    />
  )
}

export function matches(filter: string, ...values: string[]): boolean {
  const f = filter.trim().toLowerCase()
  return !f || values.some((v) => v.toLowerCase().includes(f))
}
