import { useCallback, useEffect, useRef } from 'react'

interface SplitterProps {
  /** Which side of the splitter the panel is on, so a drag right widens or narrows correctly. */
  side: 'left' | 'right'
  width: number
  min: number
  max: number
  onResize: (width: number) => void
  onToggle: () => void
  collapsed: boolean
  label: string
}

/**
 * A draggable divider with a collapse toggle.
 *
 * The canvas is the part of the screen worth the most, and fixed side panels take it whether or
 * not they have anything to say. Dragging is handled on `window` rather than on the element so
 * the pointer can leave the 6px strip mid-drag without the resize stopping — the usual reason
 * home-made splitters feel broken.
 */
export default function Splitter({ side, width, min, max, onResize, onToggle, collapsed, label }: SplitterProps) {
  const dragging = useRef(false)

  const onPointerDown = useCallback((event: React.PointerEvent) => {
    event.preventDefault()
    dragging.current = true
    document.body.classList.add('ag-resizing')
  }, [])

  useEffect(() => {
    function onMove(event: PointerEvent) {
      if (!dragging.current) {
        return
      }
      const next = side === 'left' ? event.clientX : window.innerWidth - event.clientX
      onResize(Math.round(Math.min(max, Math.max(min, next))))
    }
    function onUp() {
      dragging.current = false
      document.body.classList.remove('ag-resizing')
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
  }, [side, min, max, onResize])

  return (
    <div className={`ag-splitter ag-splitter-${side}`} onPointerDown={onPointerDown} role="separator">
      <button
        type="button"
        className="ag-splitter-toggle"
        title={collapsed ? `Show ${label}` : `Hide ${label}`}
        aria-label={collapsed ? `Show ${label}` : `Hide ${label}`}
        // The toggle sits inside the drag strip, so its own events must not start a drag.
        onPointerDown={(event) => event.stopPropagation()}
        onClick={onToggle}
      >
        {side === 'left' ? (collapsed ? '›' : '‹') : collapsed ? '‹' : '›'}
      </button>
      <span className="ag-splitter-width">{collapsed ? '' : `${width}`}</span>
    </div>
  )
}
