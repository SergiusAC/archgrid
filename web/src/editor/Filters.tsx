import { useState } from 'react'

import type { ViewFilter } from '../api/types.ts'

interface FiltersProps {
  layers: string[]
  filters: ViewFilter[]
  /** Members the filters leave out, so the panel can say what narrowing this view costs. */
  filtered: { key: string; name: string; type: string }[]
  onChange: (filters: ViewFilter[]) => void
}

/**
 * What this view draws, out of what belongs to it.
 *
 * Two kinds and no more, because two are what the repository can answer with certainty: a layer,
 * which is part of the metamodel and cannot be mistyped into meaninglessness, and a property, which
 * is what an architect actually marks things with. A "tag" is a property named tag rather than a
 * third mechanism.
 *
 * Within a kind the conditions are alternatives, across kinds they all hold — ticking a second
 * layer widens the diagram, adding a property narrows it. The other reading makes every filter
 * you add subtract, which is not what anyone means by ticking two boxes.
 */
export default function Filters({ layers, filters, filtered, onChange }: FiltersProps) {
  const [property, setProperty] = useState('')
  const [value, setValue] = useState('')

  const layerFilters = new Set(filters.filter((filter) => filter.kind === 'layer').map((filter) => filter.value))
  const properties = filters.filter((filter) => filter.kind === 'property')

  const toggleLayer = (layer: string) => {
    onChange(
      layerFilters.has(layer)
        ? filters.filter((filter) => !(filter.kind === 'layer' && filter.value === layer))
        : [...filters, { kind: 'layer', name: null, value: layer }],
    )
  }

  return (
    <section className="ag-filters">
      <h4>Show only</h4>

      <div className="ag-filter-layers">
        {layers.map((layer) => (
          <button
            key={layer}
            type="button"
            className={layerFilters.has(layer) ? 'ag-filter-on' : ''}
            onClick={() => toggleLayer(layer)}
            title={`Draw only the ${layer} layer`}
          >
            {layer}
          </button>
        ))}
      </div>

      {properties.length > 0 && (
        <ul className="ag-filter-list">
          {properties.map((filter) => (
            <li key={`${filter.name}=${filter.value ?? ''}`}>
              <code>
                {filter.name}
                {filter.value === null ? '' : `=${filter.value}`}
              </code>
              <button
                type="button"
                className="ag-tree-tiny"
                aria-label={`Remove the ${filter.name} filter`}
                onClick={() =>
                  onChange(
                    filters.filter(
                      (other) =>
                        !(other.kind === 'property' && other.name === filter.name && other.value === filter.value),
                    ),
                  )
                }
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}

      <form
        className="ag-filter-property"
        onSubmit={(event) => {
          event.preventDefault()
          if (!property.trim()) {
            return
          }
          // An empty value asks for everything that carries the property at all — "show me what
          // has an owner" is a real question and needs no wildcard syntax to ask it.
          onChange([
            ...filters,
            { kind: 'property', name: property.trim(), value: value.trim() === '' ? null : value.trim() },
          ])
          setProperty('')
          setValue('')
        }}
      >
        <input value={property} onChange={(event) => setProperty(event.target.value)} placeholder="property" />
        <input value={value} onChange={(event) => setValue(event.target.value)} placeholder="any value" />
        <button type="submit" disabled={!property.trim()}>
          Add
        </button>
      </form>

      {filters.length > 0 && (
        <div className="ag-filter-foot">
          {/* Said rather than counted in silence: a narrowed diagram must not read as the whole
              picture, which is the same reason a hidden line carries a counter. */}
          <span className="ag-muted">
            {filtered.length === 0
              ? 'Nothing is left out.'
              : `${filtered.length} left out: ${filtered
                  .slice(0, 3)
                  .map((element) => element.name)
                  .join(', ')}${filtered.length > 3 ? '…' : ''}`}
          </span>
          <button type="button" className="ag-link" onClick={() => onChange([])}>
            clear
          </button>
        </div>
      )}
    </section>
  )
}
