import { useMemo } from 'react'

import type { ElementType } from '../api/types.ts'
import { LAYER_LABELS, type Metamodel } from '../metamodel/Metamodel.ts'

interface PaletteProps {
  metamodel: Metamodel
  viewpoint: string
  onViewpointChange: (code: string) => void
  onCreate: (type: ElementType) => void
  profiles: { name: string; conceptType: string }[]
  onCreateProfiled: (profileName: string) => void
  busy: boolean
}

/**
 * The element palette, limited to the current viewpoint.
 *
 * This is what makes sixty element types usable. The model stays complete; the palette shows
 * ten or fifteen. Model completeness and palette completeness are different things (ADR-0002),
 * and conflating them is the main reason people find ArchiMate unapproachable.
 */
export default function Palette({
  metamodel,
  viewpoint,
  onViewpointChange,
  onCreate,
  profiles,
  onCreateProfiled,
  busy,
}: PaletteProps) {
  const grouped = useMemo(() => {
    const types = metamodel.palette(viewpoint)
    const groups = new Map<string, ElementType[]>()
    for (const type of types) {
      const label = LAYER_LABELS[type.layer]
      const bucket = groups.get(label)
      if (bucket) {
        bucket.push(type)
      } else {
        groups.set(label, [type])
      }
    }
    return [...groups.entries()]
  }, [metamodel, viewpoint])

  return (
    <aside className="ag-panel ag-palette">
      <label className="ag-field">
        <span>Viewpoint</span>
        <select value={viewpoint} onChange={(event) => onViewpointChange(event.target.value)}>
          {metamodel.viewpoints.map((option) => (
            <option key={option.code} value={option.code}>
              {option.name}
            </option>
          ))}
        </select>
      </label>

      {profiles.length > 0 && (
        <section className="ag-palette-group">
          <h3>Specialisations</h3>
          <div className="ag-palette-items">
            {profiles.map((profile) => (
              <button
                key={profile.name}
                type="button"
                className="ag-palette-item ag-profiled"
                disabled={busy}
                title={`${profile.name} — a kind of ${profile.conceptType}`}
                onClick={() => onCreateProfiled(profile.name)}
              >
                «{profile.name}»
              </button>
            ))}
          </div>
        </section>
      )}

      {grouped.map(([layer, types]) => (
        <section key={layer} className="ag-palette-group">
          <h3>{layer}</h3>
          <div className="ag-palette-items">
            {types.map((type) => (
              <button
                key={type.concept}
                type="button"
                className={`ag-palette-item ag-layer-${type.layer}`}
                disabled={busy}
                title={type.name}
                onClick={() => onCreate(type)}
              >
                {type.name}
              </button>
            ))}
          </div>
        </section>
      ))}
    </aside>
  )
}
