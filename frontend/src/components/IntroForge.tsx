import { useEffect, useRef, useState } from 'react'
import { SPEED, startScene } from '../intro/forgeScene.ts'

/** When the overlay starts to fade and when the title appears (timeline ms), and the fade (ms). */
const LEAVE_AT = 5300 / SPEED
const TITLE_AT = 4400 / SPEED
const FADE = 500

/**
 * The opening: close on the anvil face, pulling back to the whole anvil before the forge fire; a
 * white-hot blade lands on it; the camera pushes in on the blade and back; a hammer beats it, and
 * it cools through cherry red to steel. Then the page shows.
 * Decoration only: a click or any key skips it, and it never runs for people who ask for less motion.
 */
export function IntroForge({ onDone }: { onDone: () => void }) {
  const stage = useRef<HTMLDivElement>(null)
  const fire = useRef<HTMLCanvasElement>(null)
  const fx = useRef<HTMLCanvasElement>(null)
  const [leaving, setLeaving] = useState(false)
  const done = useRef(onDone)
  done.current = onDone

  useEffect(() => {
    const scene = startScene(fire.current!, fx.current!, stage.current!)
    const leave = setTimeout(() => setLeaving(true), LEAVE_AT)
    const gone = setTimeout(() => done.current(), LEAVE_AT + FADE)
    const skip = () => done.current()
    window.addEventListener('keydown', skip)
    return () => {
      scene.stop()
      clearTimeout(leave)
      clearTimeout(gone)
      window.removeEventListener('keydown', skip)
    }
  }, [])

  return (
    <div className={`intro${leaving ? ' leaving' : ''}`} onClick={() => done.current()}>
      <div className="intro-stage" ref={stage} aria-hidden="true">
        <canvas className="intro-fire" ref={fire} />
        <canvas className="intro-fx" ref={fx} />
      </div>
      <div className="intro-vignette" aria-hidden="true" />
      <div className="intro-title" aria-hidden="true" style={{ animationDelay: `${TITLE_AT}ms` }}>
        <p className="intro-name">
          <span className="intro-steel">WSDL</span>
          <span className="intro-molten">Forge</span>
        </p>
        <p className="intro-tagline">Old SOAP in the fire. REST and MCP out.</p>
      </div>
      <button
        type="button"
        className="intro-skip"
        onClick={(e) => {
          e.stopPropagation()
          done.current()
        }}
      >
        Skip intro
      </button>
    </div>
  )
}
  