import { useRef, useState, type DragEvent } from 'react'
import { SAMPLES, type SoapVersion } from '../api.ts'

interface Props {
  busy: boolean
  onFile: (file: File) => void
  onSample: (version: SoapVersion) => void
}

/** The anvil: file picker with drag and drop, plus the one-click sample WSDLs (SOAP 1.1 and 1.2). */
export function UploadPanel({ busy, onFile, onSample }: Props) {
  const input = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)

  function drop(e: DragEvent) {
    e.preventDefault()
    setDragging(false)
    const file = e.dataTransfer.files[0]
    if (file && !busy) onFile(file)
  }

  return (
    <section className="panel upload" aria-labelledby="upload-title">
      <h2 id="upload-title">
        <span className="step">1</span> Lay the raw metal on the anvil
      </h2>
      <div
        className={`dropzone${dragging ? ' dragging' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={drop}
      >
        <span className="drop-icon" aria-hidden="true">
          ⚒
        </span>
        <p>
          Drop a crusty old WSDL 1.1 <code>document/literal</code> file here, or
        </p>
        <button type="button" className="secondary" disabled={busy} onClick={() => input.current?.click()}>
          Pick one from the scrap pile
        </button>
        <input
          ref={input}
          type="file"
          accept=".wsdl,.xml,text/xml,application/xml"
          hidden
          onChange={(e) => {
            const file = e.target.files?.[0]
            e.target.value = '' // picking the same file again still triggers a run
            if (file) onFile(file)
          }}
        />
      </div>
      <div className="sample">
        <button type="button" className="primary forge-button" disabled={busy} onClick={() => onSample('1.1')}>
          <span aria-hidden="true">🔥</span> Forge the sample bank
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={() => onSample('1.2')}>
          Forge it over SOAP 1.2
        </button>
        <p className="hint">
          A dusty legacy bank with <code>GetBalance</code>, <code>Transfer</code> and <code>GetStatement</code>. Its
          SOAP service runs on this server at <code>/mock-bank/ws</code> (SOAP 1.1) and <code>/mock-bank/ws12</code>{' '}
          (SOAP 1.2), sharing one ledger. Inspect the{' '}
          <a href={SAMPLES['1.1'].url} target="_blank" rel="noreferrer">
            SOAP 1.1 WSDL
          </a>{' '}
          or the{' '}
          <a href={SAMPLES['1.2'].url} target="_blank" rel="noreferrer">
            SOAP 1.2 WSDL
          </a>
          .
        </p>
      </div>
    </section>
  )
}
