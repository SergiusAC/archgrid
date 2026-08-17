/**
 * ArchiMate line notation per relationship type.
 *
 * The arrowheads are not decoration. An architect reads direction and strength off the line
 * before reading any label: a filled diamond means "is part of", a hollow triangle means
 * "realises", a dashed line with a filled head means "flows to". Drawing every relationship as
 * a plain arrow throws away most of what the notation carries.
 *
 * Like the structural strength ordering, these shapes are the conventional ones and have not
 * been checked against the specification's figures. They are presentation rather than a
 * correctness claim, so the consequence of an error here is a diagram that reads oddly, not a
 * model that is wrong.
 */
export interface RelationshipNotation {
  /** Marker at the target end. */
  markerEnd?: string
  /** Marker at the source end — composition and aggregation put their diamond at the whole. */
  markerStart?: string
  /** SVG stroke-dasharray. */
  dash?: string
  /** Moving dashes, reserved for the dynamic relationships where flow direction is the point. */
  animated?: boolean
}

export const NOTATION: Record<string, RelationshipNotation> = {
  // Structural: the diamond sits at the whole, which is the source of the relationship.
  Composition: { markerStart: 'ag-diamond-filled' },
  Aggregation: { markerStart: 'ag-diamond-hollow' },
  Assignment: { markerStart: 'ag-ball', markerEnd: 'ag-arrow-filled' },
  Realization: { markerEnd: 'ag-triangle-hollow', dash: '2 4' },

  // Dependency.
  Serving: { markerEnd: 'ag-arrow-open' },
  Access: { markerEnd: 'ag-arrow-open-small', dash: '2 3' },
  Influence: { markerEnd: 'ag-arrow-open', dash: '6 4' },

  // Dynamic.
  Triggering: { markerEnd: 'ag-arrow-filled' },
  Flow: { markerEnd: 'ag-arrow-filled', dash: '6 4', animated: true },

  // Other. Association is the one relationship that carries no arrowhead by default.
  Specialization: { markerEnd: 'ag-triangle-hollow' },
  Association: {},
}

export function notationFor(relationshipType: string): RelationshipNotation {
  return NOTATION[relationshipType] ?? {}
}

/**
 * React Flow takes the bare marker id and wraps it in `url(#…)` itself.
 *
 * Wrapping it here as well produces `url(#url(#ag-arrow-open))`, which is not a valid
 * reference, so the arrowhead silently does not render — the line still draws, so nothing
 * looks broken enough to notice without opening the DOM.
 */
export function marker(id: string | undefined): string | undefined {
  return id
}
