import { useEffect, useRef } from 'react'

import type { RelationshipType } from '../api/types.ts'

interface RelationshipPickerProps {
  x: number
  y: number
  sourceName: string
  targetName: string
  /** Why the type on the toolbar could not be used, phrased as the server would phrase it. */
  reason: string
  permitted: RelationshipType[]
  onPick: (concept: string) => void
  onCancel: () => void
}

/**
 * Offered when the relationship type on the toolbar is not permitted between the two elements
 * just connected.
 *
 * This is the metamodel changing role. Until now it only ever said no; here it answers "not
 * that, but any of these" and the user picks. Refusing with an error message and nothing else
 * would have been correct and much less useful — the tool knows the answer, so it should offer
 * it.
 */
export default function RelationshipPicker({
  x,
  y,
  sourceName,
  targetName,
  reason,
  permitted,
  onPick,
  onCancel,
}: RelationshipPickerProps) {
  const list = useRef<HTMLDivElement>(null)

  // The picker interrupts a drag, which is a mouse gesture, but it is still a list of options and
  // arriving here should not mean reaching back for the mouse. Enter takes the first — the one the
  // metamodel considers most likely — and Escape leaves the connection undrawn.
  useEffect(() => {
    list.current?.querySelector('button')?.focus()
  }, [])

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      onCancel()
      return
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
      return
    }
    event.preventDefault()
    const buttons = [...(list.current?.querySelectorAll('button') ?? [])]
    const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
    const next = event.key === 'ArrowDown' ? at + 1 : at - 1
    buttons[(next + buttons.length) % buttons.length]?.focus()
  }

  return (
    <>
      <div className="ag-context-backdrop" onClick={onCancel} />
      <div
        ref={list}
        onKeyDown={onKeyDown}
        className="ag-picker"
        style={{
          left: Math.min(x, window.innerWidth - 300),
          top: Math.min(y, window.innerHeight - 40 - permitted.length * 34),
        }}
        role="menu"
      >
        <header>
          <strong>
            {sourceName} → {targetName}
          </strong>
          <p className="muted">{reason}</p>
        </header>
        {permitted.length === 0 ? (
          <p className="muted ag-picker-empty">
            No relationship type is permitted between these two. Nothing to choose.
          </p>
        ) : (
          <ul>
            {permitted.map((type) => (
              <li key={type.concept}>
                <button type="button" onClick={() => onPick(type.concept)}>
                  <span>{type.name}</span>
                  <small>{type.category}</small>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  )
}
