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
  return (
    <>
      <div className="ag-context-backdrop" onClick={onCancel} />
      <div
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
