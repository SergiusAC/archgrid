import { useEffect, useMemo, useRef, useState } from 'react'

import type { ElementType, RelationshipType } from '../api/types.ts'
import type { Metamodel } from '../metamodel/Metamodel.ts'

interface MagicMenuProps {
  x: number
  y: number
  /** The element the line was dragged from. */
  sourceName: string
  sourceConcept: string
  metamodel: Metamodel
  /** Only what this view's viewpoint allows, same as the palette. */
  palette: ElementType[]
  onPick: (relationship: string, elementType: ElementType) => void
  onCancel: () => void
}

/**
 * Dragged a line into empty space? Then say what should be there.
 *
 * Archi calls this the Magic Connector, and it is the single biggest saving in the editor: the
 * usual way to extend a diagram is "from this thing, something else follows", and doing that
 * through a palette costs a trip across the screen, a type, a name, and then finding a handle to
 * drag from. Here the gesture that expresses the intent creates both the element and the
 * relationship, in one commit, without the cursor leaving the canvas.
 *
 * The menu offers relationships first and elements second — Archi's default, and the right one:
 * the source is fixed, so the relationship is what narrows the choice. Nothing impermissible is
 * ever listed; the metamodel already knows the answer, so the user is never asked to guess it.
 */
export default function MagicMenu({
  x,
  y,
  sourceName,
  sourceConcept,
  metamodel,
  palette,
  onPick,
  onCancel,
}: MagicMenuProps) {
  const root = useRef<HTMLDivElement>(null)
  const [relationship, setRelationship] = useState<string | null>(null)

  /** Relationship types that lead somewhere, with the element types they lead to. */
  const options = useMemo(() => {
    const byRelationship = new Map<string, { type: RelationshipType; targets: ElementType[] }>()
    for (const target of palette) {
      for (const type of metamodel.permittedBetween(sourceConcept, target.concept)) {
        const entry = byRelationship.get(type.concept) ?? { type, targets: [] }
        entry.targets.push(target)
        byRelationship.set(type.concept, entry)
      }
    }
    return [...byRelationship.values()].sort((a, b) => a.type.name.localeCompare(b.type.name))
  }, [metamodel, sourceConcept, palette])

  const targets = options.find((option) => option.type.concept === relationship)?.targets ?? []

  useEffect(() => {
    root.current?.querySelector('button')?.focus()
  }, [relationship])

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      // One step back, then out: the second pane is a refinement of the first, not a new dialog.
      if (relationship) {
        setRelationship(null)
      } else {
        onCancel()
      }
      return
    }
    if (event.key === 'ArrowLeft' && relationship) {
      setRelationship(null)
      return
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
      return
    }
    event.preventDefault()
    const buttons = [...(root.current?.querySelectorAll('button') ?? [])]
    const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
    const next = event.key === 'ArrowDown' ? at + 1 : at - 1
    buttons[(next + buttons.length) % buttons.length]?.focus()
  }

  // The list scrolls past this, so the menu never grows taller than it can be placed.
  const height = 76 + Math.min(relationship ? targets.length : options.length, 12) * 30

  return (
    <>
      <div className="ag-context-backdrop" onClick={onCancel} />
      <div
        ref={root}
        onKeyDown={onKeyDown}
        className="ag-picker ag-magic"
        style={{
          left: Math.min(x, window.innerWidth - 300),
          top: Math.max(8, Math.min(y, window.innerHeight - height - 8)),
        }}
        role="menu"
      >
        <header>
          <strong>
            {sourceName} {relationship ? `— ${metamodel.relationshipType(relationship)?.name} →` : '→'}
          </strong>
          <p className="muted">
            {relationship
              ? 'What it connects to. Only what ArchiMate permits is listed.'
              : 'Pick the relationship, then what it leads to.'}
          </p>
        </header>

        {options.length === 0 ? (
          <p className="muted ag-picker-empty">
            Nothing this viewpoint allows can be connected to a {metamodel.elementType(sourceConcept)?.name}.
          </p>
        ) : (
          <ul>
            {!relationship &&
              options.map((option) => (
                <li key={option.type.concept}>
                  <button type="button" onClick={() => setRelationship(option.type.concept)}>
                    <span>{option.type.name}</span>
                    <small>{option.targets.length}</small>
                  </button>
                </li>
              ))}
            {relationship &&
              targets.map((target) => (
                <li key={target.concept}>
                  <button type="button" onClick={() => onPick(relationship, target)}>
                    <span>{target.name}</span>
                    <small>{target.layer}</small>
                  </button>
                </li>
              ))}
          </ul>
        )}
      </div>
    </>
  )
}
