import type { Warning } from '../api.ts'

interface Props {
  warnings: Warning[]
  /** "report": the zip was generated; "rejected": a 422 explains why it was not. */
  mode: 'report' | 'rejected'
}

interface Group {
  code: string
  message: string
  locations: string[]
}

/** One row per warning code, in the order codes first appear, so 300 warnings read as a handful of causes. */
export function groupWarnings(warnings: Warning[]): Group[] {
  const groups = new Map<string, Group>()
  for (const w of warnings) {
    const g = groups.get(w.code)
    if (g) g.locations.push(w.location)
    else groups.set(w.code, { code: w.code, message: w.message, locations: [w.location] })
  }
  return [...groups.values()].sort((a, b) => b.locations.length - a.locations.length)
}

/** Shown per location list before it collapses behind "and N more". */
const SHOWN_LOCATIONS = 3

/** Renders generator-report.json warnings and 422 warnings the same way (README: same shape). */
export function WarningsPanel({ warnings, mode }: Props) {
  if (warnings.length === 0) {
    return mode === 'report' ? (
      <p className="warnings-none">Flawless casting: every feature in the WSDL is supported.</p>
    ) : null
  }
  const groups = groupWarnings(warnings)
  return (
    <div className={`warnings ${mode}`}>
      <h3>
        {warnings.length} warning{warnings.length === 1 ? '' : 's'} from {groups.length} cause
        {groups.length === 1 ? '' : 's'}
        {mode === 'report' ? ': slag the forge skimmed off (skipped or simplified)' : ''}
      </h3>
      <table>
        <thead>
          <tr>
            <th scope="col">Code</th>
            <th scope="col">Count</th>
            <th scope="col">What</th>
            <th scope="col">Where</th>
          </tr>
        </thead>
        <tbody>
          {groups.map((g) => (
            <tr key={g.code}>
              <td>
                <code>{g.code}</code>
              </td>
              <td className="num">{g.locations.length}</td>
              <td>{g.message}</td>
              <td>
                <Locations locations={g.locations} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Locations({ locations }: { locations: string[] }) {
  const head = locations.slice(0, SHOWN_LOCATIONS)
  const rest = locations.slice(SHOWN_LOCATIONS)
  return (
    <>
      {head.map((l, i) => (
        <code key={i} className="loc">
          {l}
        </code>
      ))}
      {rest.length > 0 && (
        <details>
          <summary>and {rest.length} more</summary>
          {rest.map((l, i) => (
            <code key={i} className="loc">
              {l}
            </code>
          ))}
        </details>
      )}
    </>
  )
}
