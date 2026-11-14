import { useRef, useState, type DragEvent } from 'react'
import { SAMPLES, type SoapVersion } from '../api.ts'

interface Props {
  busy: boolean
  onFile: (file: File) => void
  onSample: (version: SoapVersion) => void
}

/** File picker with drag and drop, plus the one-click sample WSDLs (SOAP 1.1 and 1.2). */
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
      <h2 id="upload-title">1. Summon a WSDL</h2>
      <div
        className={`dropzone${dragging ? ' dragging' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={drop}
      >
        <p>
          Drop a WSDL 1.1 <code>document/literal</code> file here, or
        </p>
        <button type="button" className="secondary" disabled={busy} onClick={() => input.current?.click()}>
          Choose a file
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
        <button type="button" className="primary" disabled={busy} onClick={() => onSample('1.1')}>
          Resurrect the sample bank WSDL
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={() => onSample('1.2')}>
          Same bank over SOAP 1.2
        </button>
        <p className="hint">
          A legacy bank with <code>GetBalance</code>, <code>Transfer</code> and <code>GetStatement</code>. Its SOAP
          service runs on this server at <code>/mock-bank/ws</code> (SOAP 1.1) and <code>/mock-bank/ws12</code> (SOAP
          1.2), sharing one ledger. View the{' '}
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
