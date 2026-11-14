import { useRef, useState, type DragEvent } from 'react'

interface Props {
  busy: boolean
  onFile: (file: File) => void
  onSample: () => void
}

/** File picker with drag and drop, plus the one-click sample WSDL. */
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
        <button type="button" className="primary" disabled={busy} onClick={onSample}>
          Resurrect the sample bank WSDL
        </button>
        <p className="hint">
          A legacy bank with <code>GetBalance</code>, <code>Transfer</code> and <code>GetStatement</code>. Its SOAP
          service runs on this server at <code>/mock-bank/ws</code>.{' '}
          <a href="/api/sample-wsdl" target="_blank" rel="noreferrer">
            View the WSDL
          </a>
        </p>
      </div>
    </section>
  )
}
