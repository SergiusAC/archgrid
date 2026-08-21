import { useLayoutEffect, useRef, useState } from 'react'

import type { ModelElement, ModelRelationship } from '../api/types.ts'

export interface LinkTarget {
  /** The line that was right-clicked — one of possibly several drawing this relationship (V15). */
  line: string
  /** The relationship it stands for. */
  key: string
  x: number
  y: number
}

interface LinkMenuProps {
  target: LinkTarget
  relationship: ModelRelationship
  elementsByKey: Record<string, ModelElement>
  /** How many lines draw this relationship on this view. */
  lineCount: number
  onClose: () => void
  onHide: () => void
  onDelete: () => void
  /** A second line for the same relationship — how it reaches a second copy (V15). */
  onDrawAgain: () => void
  /** Removes this line; offered only while another one remains. */
  onRemoveLine: () => void
}

/**
 * Right-click on a line.
 *
 * The same two-way choice the element menu offers, and for the same reason: leaving a line off one
 * diagram and unsaying it in the repository are different acts, and the difference has to be
 * visible before the click (ADR-0003). Hiding is what nesting already does silently — the model
 * keeps asserting the relationship and every other view keeps drawing it (ADR-0011).
 */
export default function LinkMenu({
  target,
  relationship,
  elementsByKey,
  lineCount,
  onClose,
  onHide,
  onDelete,
  onDrawAgain,
  onRemoveLine,
}: LinkMenuProps) {
  const menuRef = useRef<HTMLDivElement>(null)
  const [placement, setPlacement] = useState({ left: target.x, top: target.y })

  // Measured, like the element menu: a line near the bottom of the window would otherwise put
  // "Delete from the model" off-screen.
  useLayoutEffect(() => {
    const node = menuRef.current
    if (!node) {
      return
    }
    const { width, height } = node.getBoundingClientRect()
    const margin = 8
    setPlacement({
      left: Math.max(margin, Math.min(target.x, window.innerWidth - width - margin)),
      top: Math.max(margin, Math.min(target.y, window.innerHeight - height - margin)),
    })
  }, [target.x, target.y])

  const source = elementsByKey[relationship.source]?.name ?? relationship.source
  const destination = elementsByKey[relationship.target]?.name ?? relationship.target

  return (
    <>
      <div className="ag-context-backdrop" onClick={onClose} onContextMenu={(event) => event.preventDefault()} />
      <div ref={menuRef} className="ag-context ag-context-link" style={placement} role="menu">
        <header className="ag-context-head">
          <div className="ag-context-name">{relationship.name ?? relationship.type}</div>
          <div className="ag-context-type">
            {source} → {destination}
          </div>
          <code>{relationship.key}</code>
        </header>

        <div className="ag-context-actions">
          {/* Line-level acts above the relationship-level ones: they change where things are
              drawn, never what the model says (ADR-0014). */}
          <div className="ag-context-row">
            <button type="button" onClick={onDrawAgain} title="Draw this relationship with a second line">
              Draw again
            </button>
            {lineCount > 1 && (
              <button type="button" onClick={onRemoveLine} title="Remove this line; the relationship stays">
                Remove this line
              </button>
            )}
          </div>
          <button type="button" onClick={onHide}>
            Hide on this view
            <small>Stays in the model, drawn on other views</small>
          </button>
          <button type="button" className="ag-danger" onClick={onDelete}>
            Delete from the model
            <small>Permanent, everywhere</small>
          </button>
        </div>
      </div>
    </>
  )
}
