import { useMemo, useState } from 'react'

import type { ModelElement, ModelRelationship } from '../api/types.ts'
import { LAYER_LABELS, type Metamodel, layerOrder } from '../metamodel/Metamodel.ts'

interface ExplorerProps {
  elements: ModelElement[]
  relationships: ModelRelationship[]
  hidden: Record<string, true>
  metamodel: Metamodel
  selected: string[]
  onSelect: (key: string) => void
  onAddToView: (key: string) => void
}

/**
 * Every element in the model, whether or not it is on this diagram.
 *
 * This is where the product's central claim becomes visible: the model is the thing, and a
 * diagram is one view of part of it. An explorer that only listed what is currently drawn
 * would quietly teach the opposite (ADR-0003).
 */
export default function Explorer({
  elements,
  relationships,
  hidden,
  metamodel,
  selected,
  onSelect,
  onAddToView,
}: ExplorerProps) {
  const [query, setQuery] = useState('')

  const degree = useMemo(() => {
    const counts: Record<string, number> = {}
    for (const relationship of relationships) {
      counts[relationship.source] = (counts[relationship.source] ?? 0) + 1
      counts[relationship.target] = (counts[relationship.target] ?? 0) + 1
    }
    return counts
  }, [relationships])

  const grouped = useMemo(() => {
    const needle = query.trim().toLowerCase()
    const matching = elements.filter((element) => {
      if (!needle) {
        return true
      }
      // Search names, keys and type — an architect looking for "kafka" may remember any of them.
      return (
        element.name.toLowerCase().includes(needle) ||
        element.key.includes(needle) ||
        element.type.toLowerCase().includes(needle) ||
        element.conceptType.toLowerCase().includes(needle)
      )
    })

    const groups = new Map<string, ModelElement[]>()
    for (const element of matching) {
      const layer = metamodel.elementType(element.conceptType)?.layer ?? 'other'
      const bucket = groups.get(layer)
      if (bucket) {
        bucket.push(element)
      } else {
        groups.set(layer, [element])
      }
    }
    return [...groups.entries()]
      .sort(([a], [b]) => layerOrder(a as never) - layerOrder(b as never))
      .map(([layer, items]) => [layer, items.sort((a, b) => a.name.localeCompare(b.name))] as const)
  }, [elements, query, metamodel])

  const total = elements.length
  const shown = grouped.reduce((sum, [, items]) => sum + items.length, 0)

  return (
    <div className="ag-explorer">
      <label className="ag-field">
        <span>Search</span>
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="name, key or type"
        />
      </label>
      <p className="ag-muted ag-explorer-count">
        {query ? `${shown} of ${total}` : `${total} element${total === 1 ? '' : 's'}`} in the model
      </p>

      {grouped.map(([layer, items]) => (
        <section key={layer} className="ag-palette-group">
          <h3>{LAYER_LABELS[layer as keyof typeof LAYER_LABELS] ?? layer}</h3>
          <ul className="ag-explorer-list">
            {items.map((element) => {
              const notOnView = Boolean(hidden[element.key])
              // The concept's display name, not the raw concept, so the explorer and the
              // canvas call the same type the same thing.
              const typeLabel =
                element.profile ?? metamodel.elementType(element.conceptType)?.name ?? element.conceptType
              return (
                <li key={element.key} className={selected.includes(element.key) ? 'ag-explorer-selected' : ''}>
                  <button
                    type="button"
                    className="ag-explorer-item"
                    onClick={() => onSelect(element.key)}
                    title={`${typeLabel} · ${degree[element.key] ?? 0} relationship(s)`}
                  >
                    <span className="ag-explorer-name">{element.name}</span>
                    <span className="ag-explorer-meta">
                      {element.profile ? `«${element.profile}»` : typeLabel}
                      {degree[element.key] ? ` · ${degree[element.key]}` : ''}
                    </span>
                  </button>
                  {notOnView && (
                    <button
                      type="button"
                      className="ag-link ag-explorer-add"
                      onClick={() => onAddToView(element.key)}
                      title="This element is in the model but not on this view"
                    >
                      add to view
                    </button>
                  )}
                </li>
              )
            })}
          </ul>
        </section>
      ))}

      {total === 0 && <p className="muted">The model is empty. Add an element from the palette.</p>}
      {total > 0 && shown === 0 && <p className="muted">Nothing matches “{query}”.</p>}
    </div>
  )
}
