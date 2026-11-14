import type { Warning } from '../api.ts'

interface Props {
  warnings: Warning[]
  /** "report": the zip was generated; "rejected": a 422 explains why it was not. */
  mode: 'report' | 'rejected'
}

/** Renders generator-report.json warnings and 422 warnings the same way (README: same shape). */
export function WarningsPanel({ warnings, mode }: Props) {
  if (warnings.length === 0) {
    return mode === 'report' ? (
      <p className="warnings-none">No warnings. Every feature in the WSDL is supported.</p>
    ) : null
  }
  return (
    <div className={`warnings ${mode}`}>
      <h3>
        {warnings.length} warning{warnings.length === 1 ? '' : 's'}
        {mode === 'report' ? ': these parts of the WSDL were skipped or simplified' : ''}
      </h3>
      <table>
        <thead>
          <tr>
            <th scope="col">Code</th>
            <th scope="col">Where</th>
            <th scope="col">What</th>
          </tr>
        </thead>
        <tbody>
          {warnings.map((w, i) => (
            <tr key={`${w.code}-${w.location}-${i}`}>
              <td>
                <code>{w.code}</code>
              </td>
              <td>
                <code>{w.location}</code>
              </td>
              <td>{w.message}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
