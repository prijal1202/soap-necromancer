import { useMemo, useState } from 'react'
import { countOf, indentXml, type Preview, type Sample } from '../preview.ts'
import { CodeBlock } from './common.tsx'

/** A sample REST request next to the exact SOAP request the generated proxy sends for it. */
export function SampleView({ preview }: { preview: Preview | null }) {
  const samples = preview?.samples ?? []
  const first = Math.max(
    0,
    samples.findIndex((s) => s.envelope && s.decimalPaths.length > 0),
  )
  const [index, setIndex] = useState(first)
  const [exact, setExact] = useState(false)

  if (!preview) {
    return <p className="muted">This zip has no preview data, so there is no sample to show.</p>
  }
  if (preview.truncated) return <p className="note warn">{preview.truncated}</p>
  const sample = samples[index] ?? samples[0]
  if (!sample) return <p className="muted">No operations.</p>

  return (
    <div className="sample-view">
      <div className="toolbar">
        <label className="select">
          Operation{' '}
          <select value={index} onChange={(e) => setIndex(Number(e.target.value))}>
            {samples.map((s, i) => (
              <option key={s.operation} value={i}>
                {s.operation}
                {s.decimalPaths.length > 0 ? ' (has decimals)' : ''}
              </option>
            ))}
          </select>
        </label>
        <div className="segmented" role="group" aria-label="Envelope layout">
          <button type="button" className="secondary small" aria-pressed={!exact} onClick={() => setExact(false)}>
            Indented
          </button>
          <button type="button" className="secondary small" aria-pressed={exact} onClick={() => setExact(true)}>
            Exact bytes
          </button>
        </div>
      </div>
      {sample.omitted || !sample.request || !sample.envelope ? (
        <p className="note warn">{sample.omitted ?? 'No sample for this operation.'}</p>
      ) : (
        <Pair sample={sample} preview={preview} exact={exact} />
      )}
    </div>
  )
}

function Pair({ sample, preview, exact }: { sample: Sample; preview: Preview; exact: boolean }) {
  const envelope = sample.envelope!
  const shown = useMemo(() => (exact ? envelope : indentXml(envelope)), [envelope, exact])
  const decimal = preview.sampleDecimal
  const inRequest = countOf(sample.request!, decimal)
  const inEnvelope = countOf(envelope, decimal)
  const http = [
    `POST ${preview.endpoint ?? '<SOAP_ENDPOINT>'}`,
    ...sample.soapHeaders.map(([k, v]) => `${k}: ${v}`),
  ].join('\n')

  return (
    <>
      <div className="pair">
        <CodeBlock
          title={
            <>
              REST request to the generated proxy: <code>POST {sample.path}</code>
            </>
          }
          text={sample.request!}
          highlight={decimal}
        />
        <div className="soap-side">
          <CodeBlock title={`SOAP ${preview.soapVersion} request line and headers`} text={http + '\n'} />
          <CodeBlock title={`SOAP ${preview.soapVersion} envelope the generated proxy sends`} text={shown} highlight={decimal} />
        </div>
      </div>
      {sample.decimalPaths.length > 0 ? (
        <p className={`note ${inEnvelope === inRequest && inRequest === sample.decimalPaths.length ? 'ok' : 'warn'}`}>
          {inEnvelope === inRequest && inRequest === sample.decimalPaths.length ? '✓ ' : ''}
          <mark>{decimal}</mark> appears {inRequest}× in the JSON and {inEnvelope}× in the envelope (
          {sample.decimalPaths.map((p, i) => (
            <span key={p}>
              {i > 0 && ', '}
              <code>{p}</code>
            </span>
          ))}
          ): same digits, same scale. The generated proxy reads JSON numbers as <code>BigDecimal</code> and writes
          them in plain notation, so no value passes through floating point.
        </p>
      ) : (
        <p className="note">
          This operation has no decimal fields. Pick one marked “has decimals” to see exact decimal handling.
        </p>
      )}
      <p className="muted small-text">
        Built by the same converter and SOAP client code the generated proxy ships, from a sample request with every
        required field. Strings, numbers and dates are placeholders.
        {exact ? ' Exact bytes: the envelope as sent, on one line.' : ' Indented for reading; the proxy sends it on one line.'}
      </p>
    </>
  )
}
