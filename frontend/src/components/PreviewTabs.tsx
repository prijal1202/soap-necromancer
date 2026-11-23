import { useState, type KeyboardEvent, type ReactNode } from 'react'
import type { Warning } from '../api.ts'
import type { ArtifactGroup } from '../artifacts.ts'
import type { ModelSummary, Preview, Tool } from '../preview.ts'
import { ArtifactTabs } from './ArtifactTabs.tsx'
import { McpView } from './McpView.tsx'
import { OpenApiView } from './OpenApiView.tsx'
import { ReportView } from './ReportView.tsx'
import { SampleView } from './SampleView.tsx'

interface Props {
  preview: Preview | null
  model: ModelSummary | null
  tools: Tool[]
  warnings: Warning[]
  yaml: string | null
  groups: ArtifactGroup[]
}

interface Tab {
  id: string
  label: string
  render: () => ReactNode
}

/** The in-browser preview of the zip. Only the open tab is rendered. */
export function PreviewTabs({ preview, model, tools, warnings, yaml, groups }: Props) {
  const [active, setActive] = useState(0)
  const tabs: Tab[] = [
    { id: 'report', label: 'Conversion Report', render: () => <ReportView model={model} warnings={warnings} /> },
    { id: 'openapi', label: 'OpenAPI 3.0 Spec', render: () => <OpenApiView openapi={preview?.openapi ?? null} yaml={yaml} /> },
    { id: 'mcp', label: 'MCP Server Tools', render: () => <McpView tools={tools} mcp={preview?.mcp ?? null} /> },
    { id: 'sample', label: 'JSON ↔ SOAP Sample', render: () => <SampleView preview={preview} /> },
    { id: 'files', label: 'All files', render: () => <ArtifactTabs groups={groups} /> },
  ]
  const tab = tabs[active]!

  /** WAI-ARIA tabs pattern: arrows move between tabs (wrapping), Home and End jump to the ends. */
  function onTabKey(e: KeyboardEvent) {
    const last = tabs.length - 1
    const next =
      e.key === 'ArrowRight' ? (active === last ? 0 : active + 1)
      : e.key === 'ArrowLeft' ? (active === 0 ? last : active - 1)
      : e.key === 'Home' ? 0
      : e.key === 'End' ? last
      : -1
    if (next < 0) return
    e.preventDefault()
    setActive(next)
    document.getElementById(`preview-tab-${tabs[next]!.id}`)?.focus()
  }

  return (
    <div className="preview">
      <div role="tablist" aria-label="Preview of the generated zip" className="tabs preview-tabs" onKeyDown={onTabKey}>
        {tabs.map((t, i) => (
          <button
            key={t.id}
            id={`preview-tab-${t.id}`}
            role="tab"
            type="button"
            aria-selected={i === active}
            aria-controls={i === active ? `preview-panel-${t.id}` : undefined}
            tabIndex={i === active ? 0 : -1}
            onClick={() => setActive(i)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`preview-panel-${tab.id}`} aria-labelledby={`preview-tab-${tab.id}`} className="tabpanel">
        {tab.render()}
      </div>
    </div>
  )
}
