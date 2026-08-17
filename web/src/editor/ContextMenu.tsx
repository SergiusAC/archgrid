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
  onClose: () => void
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
export default function ContextMenu({
  target,
  element,
  relationships,
  elementsByKey,
  metamodel,
  onClose,
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
  }, [target.x, target.y, used.length, properties.length])

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

        <div className="ag-context-actions">
          <button type="button" onClick={onRemoveFromView}>
            Remove from this view
            <small>Stays in the model</small>
          </button>
          <button type="button" className="ag-danger" onClick={onDeleteFromModel}>
            Delete from the model
            <small>
              {used.length > 0
                ? `Also deletes ${used.length} relationship${used.length === 1 ? '' : 's'}`
                : 'Permanent, everywhere'}
            </small>
          </button>
        </div>
      </div>
    </>
  )
}
