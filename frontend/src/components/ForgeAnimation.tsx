import { useEffect, useState } from 'react'

/** What the forge is "doing", in order. Decoration only: the real status line is announced separately. */
const STEPS = [
  'Stoking the furnace…',
  'Heating the WSDL until the XML glows…',
  'Hammering XSD types into JSON Schema…',
  'Folding SOAP envelopes into REST endpoints…',
  'Tempering one MCP tool per operation…',
  'Quenching the round-trip tests…',
]

/** A hammer striking an anvil, throwing sparks. Purely visual, hidden from screen readers. */
export function ForgeAnimation({ note }: { note: string }) {
  const [step, setStep] = useState(0)
  useEffect(() => {
    const timer = setInterval(() => setStep((s) => (s + 1) % STEPS.length), 1400)
    return () => clearInterval(timer)
  }, [])

  return (
    <div className="forging">
      <svg className="anvil-scene" viewBox="0 0 160 110" aria-hidden="true">
        <g className="sparks">
          <circle className="spark s1" cx="86" cy="52" r="2.2" />
          <circle className="spark s2" cx="86" cy="52" r="1.8" />
          <circle className="spark s3" cx="86" cy="52" r="2" />
          <circle className="spark s4" cx="86" cy="52" r="1.5" />
          <circle className="spark s5" cx="86" cy="52" r="1.7" />
        </g>
        <rect className="ingot" x="70" y="48" width="34" height="7" rx="2" />
        <path
          className="anvil"
          d="M20 56h96c9 0 16 4 16 10H106c-3 0-5 2-5 5v6h9v8H56v-8h9v-6c0-3-2-5-5-5H36c-9 0-16-4-16-10z"
        />
        <rect className="anvil-base" x="46" y="93" width="74" height="8" rx="2" />
        <g className="hammer">
          <rect className="hammer-handle" x="112" y="4" width="6" height="46" rx="3" transform="rotate(35 115 27)" />
          <rect className="hammer-head" x="84" y="10" width="34" height="16" rx="3" transform="rotate(35 101 18)" />
        </g>
      </svg>
      <div className="forging-text">
        <p className="forging-step" aria-hidden="true">
          {STEPS[step]}
        </p>
        <p className="meta">{note}</p>
      </div>
    </div>
  )
}
