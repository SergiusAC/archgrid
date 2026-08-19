import { useLayoutEffect, useRef, useState } from 'react'

import type { ModelElement, ModelRelationship } from '../api/types.ts'
import type { Metamodel } from '../metamodel/Metamodel.ts'

export interface ContextTarget {
  elementKey: string
  x: number
  y: number
}

interface ContextMenuProps {
  target: ContextTarget
  element: ModelElement
  relationships: ModelRelationship[]
  elementsByKey: Record<string, ModelElement>
  metamodel: Metamodel
  /** The views that show this element, or null while the answer is still on its way. */
  views: { key: string; name: string }[] | null
  currentView: string | null
  onClose: () => void
  onCopy: () => void
  onDuplicate: () => void
  onRestack: (to: 'front' | 'back') => void
  onRemoveFromView: () => void
  onDeleteFromModel: () => void
}

/**
 * Right-click inspector for an element.
 *
 * Shows what the element is and, crucially, everywhere it is used, because the two delete
 * options below it mean very different things and the difference has to be visible before the
 * click rather than explained after it (ADR-0003).
 */
/**
 * What deleting this element would cost, in the terms the architect thinks in.
 *
 * Counts, not "permanent, everywhere": the number of diagrams that would change is the thing
 * they can weigh, and it is exactly what a repository-first tool is able to answer.
 */
function consequence(relationships: number, views: number | null): string {
  const parts: string[] = []
  if (relationships > 0) {
    // Deleted, not "removed from": a relationship without one of its ends is not a relationship.
    parts.push(`deletes ${relationships} relationship${relationships === 1 ? '' : 's'}`)
  }
  if (views && views > 0) {
    parts.push(`removes it from ${views} view${views === 1 ? '' : 's'}`)
  }
  return parts.length > 0 ? `Also ${parts.join(' and ')}` : 'Permanent, everywhere'
}

export default function ContextMenu({
  target,
  element,
  relationships,
  elementsByKey,
  metamodel,
  views,
  currentView,
  onClose,
  onCopy,
  onDuplicate,
  onRestack,
  onRemoveFromView,
  onDeleteFromModel,
}: ContextMenuProps) {
  const type = metamodel.elementType(element.conceptType)
  const used = relationships.filter(
    (relationship) => relationship.source === element.key || relationship.target === element.key,
  )
  const properties = Object.entries(element.properties)

  // Right-clicking near an edge of the window would otherwise put the menu — including the two
  // delete buttons — partly off-screen. Measured rather than assumed, because the height
  // depends on how many relationships the element has.
  const menuRef = useRef<HTMLDivElement>(null)
  const [placement, setPlacement] = useState({ left: target.x, top: target.y })

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
  }, [target.x, target.y, used.length, properties.length, views?.length])

  return (
    <>
      <div className="ag-context-backdrop" onClick={onClose} onContextMenu={(event) => event.preventDefault()} />
      <div ref={menuRef} className="ag-context" style={placement} role="menu">
        <header className="ag-context-head">
          <div className="ag-context-name">{element.name}</div>
          <div className="ag-context-type">
            {element.profile ? (
              <>
                <span className="ag-stereotype">«{element.profile}»</span> {type?.name ?? element.conceptType}
              </>
            ) : (
              (type?.name ?? element.conceptType)
            )}
          </div>
          <code>{element.key}</code>
        </header>

        <dl className="ag-context-facts">
          <dt>Layer</dt>
          <dd>{type?.layer ?? '—'}</dd>
          <dt>Aspect</dt>
          <dd>{type?.aspect ?? '—'}</dd>
          {element.documentation && (
            <>
              <dt>Documentation</dt>
              <dd>{element.documentation}</dd>
            </>
          )}
          {properties.length > 0 && (
            <>
              <dt>Properties</dt>
              <dd>
                {properties.map(([key, value]) => (
                  <div key={key}>
                    <code>{key}</code> {value}
                  </div>
                ))}
              </dd>
            </>
          )}
        </dl>

        <section className="ag-context-usage">
          <h4>Used in {used.length} relationship{used.length === 1 ? '' : 's'}</h4>
          {used.length > 0 && (
            <ul>
              {used.slice(0, 8).map((relationship) => {
                const other =
                  relationship.source === element.key
                    ? elementsByKey[relationship.target]
                    : elementsByKey[relationship.source]
                const direction = relationship.source === element.key ? '→' : '←'
                return (
                  <li key={relationship.key}>
                    {relationship.type} {direction} {other?.name ?? '?'}
                  </li>
                )
              })}
              {used.length > 8 && <li className="ag-muted">…and {used.length - 8} more</li>}
            </ul>
          )}
        </section>

        {/*
          Where else this element is drawn. It is the fact that turns "delete from the model" from
          a leap into a decision, so it sits directly above the button (ADR-0009).
        */}
        <section className="ag-context-usage ag-context-views">
          <h4>{views ? `On ${views.length} view${views.length === 1 ? '' : 's'}` : 'Views…'}</h4>
          {views && views.length > 0 && (
            <ul>
              {views.slice(0, 6).map((view) => (
                <li key={view.key} className={view.key === currentView ? 'ag-muted' : undefined}>
                  {view.name}
                  {view.key === currentView ? ' (this one)' : ''}
                </li>
              ))}
              {views.length > 6 && <li className="ag-muted">…and {views.length - 6} more</li>}
            </ul>
          )}
        </section>

        <div className="ag-context-actions">
          {/* Copy takes the element to another view; duplicating makes a second one. Different
              acts, so different buttons — the distinction is the same one the delete pair makes. */}
          <div className="ag-context-row">
            <button type="button" onClick={onCopy}>
              Copy
            </button>
            <button type="button" onClick={onDuplicate}>
              Duplicate
            </button>
          </div>
          {/* Stacking is presentation, not model: it rides with the coordinates and is kept by
              Save like every other part of the arrangement (ADR-0003). */}
          <div className="ag-context-row">
            <button type="button" onClick={() => onRestack('front')}>
              Bring to front
            </button>
            <button type="button" onClick={() => onRestack('back')}>
              Send to back
            </button>
          </div>
          <button type="button" onClick={onRemoveFromView}>
            Remove from this view
            <small>Stays in the model</small>
          </button>
          <button type="button" className="ag-danger" onClick={onDeleteFromModel}>
            Delete from the model
            <small>{consequence(used.length, views ? views.length : null)}</small>
          </button>
        </div>
      </div>
    </>
  )
}
