import type { Warning } from '../api.ts'
import type { ModelElement, ModelSummary } from '../preview.ts'
import { groupWarnings, WarningsPanel } from './WarningsPanel.tsx'

interface Props {
  model: ModelSummary | null
  warnings: Warning[]
}

/** What the generator converted (from model.json) next to what it skipped (generator-report.json). */
export function ReportView({ model, warnings }: Props) {
  const causes = groupWarnings(warnings).length
  return (
    <div className="report">
      <div className="stats">
        <Stat value={model?.operations.length ?? 0} label="operations converted" tone="ok" />
        <Stat value={model?.complexTypes ?? 0} label="complex types" tone="ok" />
        <Stat value={(model?.enumTypes ?? 0) + (model?.simpleTypes ?? 0)} label="enum and simple types" tone="ok" />
        <Stat value={model?.fields ?? 0} label="fields mapped" tone="ok" />
        <Stat value={warnings.length} label={`skipped or simplified (${causes} cause${causes === 1 ? '' : 's'})`} tone={warnings.length ? 'warn' : 'ok'} />
      </div>

      {model && (
        <section aria-labelledby="converted-title">
          <h3 id="converted-title" className="section-title ok">
            Converted
          </h3>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">SOAP operation</th>
                  <th scope="col">REST endpoint</th>
                  <th scope="col">SOAP action</th>
                  <th scope="col">Request element</th>
                  <th scope="col">Response element</th>
                </tr>
              </thead>
              <tbody>
                {model.operations.map((op) => (
                  <tr key={op.name}>
                    <td>
                      <code>{op.name}</code>
                    </td>
                    <td>
                      <code>POST /{op.name}</code>
                    </td>
                    <td>{op.soapAction ? <code className="loc">{op.soapAction}</code> : <span className="muted">none</span>}</td>
                    <td>
                      <Element e={op.input} />
                    </td>
                    <td>
                      <Element e={op.output} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {model.emptyTypes.length > 0 && (
            <p className="note warn">
              {model.emptyTypes.length} complex type{model.emptyTypes.length === 1 ? '' : 's'} came through with no
              fields, so {model.emptyTypes.length === 1 ? 'it maps' : 'they map'} to <code>{'{}'}</code>:{' '}
              {model.emptyTypes.map((t, i) => (
                <span key={t}>
                  {i > 0 && ', '}
                  <code>{t}</code>
                </span>
              ))}
              . The skipped list below says why.
            </p>
          )}
        </section>
      )}

      <section aria-labelledby="skipped-title">
        <h3 id="skipped-title" className="section-title warn">
          Skipped or simplified
        </h3>
        <p className="muted small-text">
          Each row is one reason. The location is the path through the WSDL to the element the generator left out or
          simplified. Nothing was fetched from the network: imported schemas are reported here instead.
        </p>
        <WarningsPanel warnings={warnings} mode="report" />
      </section>
    </div>
  )
}

function Stat({ value, label, tone }: { value: number; label: string; tone: 'ok' | 'warn' }) {
  return (
    <div className={`stat ${tone}`}>
      <span className="stat-value">{value.toLocaleString('en')}</span>
      <span className="stat-label">{label}</span>
    </div>
  )
}

function Element({ e }: { e: ModelElement }) {
  return (
    <>
      <code>{e.element}</code>
      <span className="loc">
        type <code>{e.typeId}</code>
        {e.fields !== null && (
          <span className={e.fields === 0 ? 'warn-text' : undefined}>
            {' '}
            · {e.fields} field{e.fields === 1 ? '' : 's'}
          </span>
        )}
      </span>
    </>
  )
}
