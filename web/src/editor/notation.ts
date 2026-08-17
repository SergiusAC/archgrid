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
 *
 * Markers carry geometry and a class, never a colour. Colour comes from CSS so that the dark
 * theme keeps working and a selected line can tint its own arrowhead.
 */

/** A JointJS marker: an SVG element name plus its attributes. */
export type Marker = Record<string, string | number>

export interface RelationshipNotation {
  /** Marker at the target end. */
  markerEnd?: Marker
  /** Marker at the source end — composition and aggregation put their diamond at the whole. */
  markerStart?: Marker
  /** SVG stroke-dasharray. */
  dash?: string
}

const filledArrow: Marker = { type: 'path', d: 'M 12 -5 0 0 12 5 z', class: 'ag-marker-filled' }
const openArrow: Marker = { type: 'path', d: 'M 12 -6 0 0 12 6', class: 'ag-marker-open' }
const openArrowSmall: Marker = { type: 'path', d: 'M 9 -4 0 0 9 4', class: 'ag-marker-open' }
const hollowTriangle: Marker = { type: 'path', d: 'M 14 -7 0 0 14 7 z', class: 'ag-marker-hollow' }
const filledDiamond: Marker = { type: 'path', d: 'M 0 0 9 -6 18 0 9 6 z', class: 'ag-marker-filled' }
const hollowDiamond: Marker = { type: 'path', d: 'M 0 0 9 -6 18 0 9 6 z', class: 'ag-marker-hollow' }
const ball: Marker = { type: 'circle', r: 4, cx: 4, cy: 0, class: 'ag-marker-filled' }

export const NOTATION: Record<string, RelationshipNotation> = {
  // Structural: the diamond sits at the whole, which is the source of the relationship.
  Composition: { markerStart: filledDiamond },
  Aggregation: { markerStart: hollowDiamond },
  Assignment: { markerStart: ball, markerEnd: filledArrow },
  Realization: { markerEnd: hollowTriangle, dash: '3 5' },

  // Dependency.
  Serving: { markerEnd: openArrow },
  Access: { markerEnd: openArrowSmall, dash: '2 3' },
  Influence: { markerEnd: openArrow, dash: '6 4' },

  // Dynamic.
  Triggering: { markerEnd: filledArrow },
  Flow: { markerEnd: filledArrow, dash: '6 4' },

  // Other. Association is the one relationship that carries no arrowhead by default.
  Specialization: { markerEnd: hollowTriangle },
  Association: {},
}

export function notationFor(relationshipType: string): RelationshipNotation {
  return NOTATION[relationshipType] ?? {}
}
