/**
 * The shape vocabulary of ArchiMate: body outline and the type glyph in the corner.
 *
 * Half of what the notation carries is geometric. A reader tells a service from a component
 * before reading a single word, because one is a rounded pill and the other a box with two tabs.
 * Drawing every concept as the same rectangle throws that away and leaves a flowchart.
 *
 * The glyph is chosen by the *kind* of concept, not by its layer, which is how ArchiMate itself
 * works: Business Service, Application Service and Technology Service share one icon and differ
 * only in colour.
 */

export type BodyShape = 'sharp' | 'rounded' | 'stadium' | 'dot'

/**
 * Behaviour is rounded, structure is square, a service is a pill, a junction is a dot.
 *
 * Motivation elements are octagons in the specification; they are drawn rounded here until the
 * body can be a polygon, and that is a known gap rather than a decision.
 */
export function bodyShape(concept: string): BodyShape {
  if (concept === 'Junction') {
    return 'dot'
  }
  if (concept.endsWith('Service')) {
    return 'stadium'
  }
  if (
    concept.endsWith('Process') ||
    concept.endsWith('Function') ||
    concept.endsWith('Interaction') ||
    concept.endsWith('Event')
  ) {
    return 'rounded'
  }
  return 'sharp'
}

/**
 * Glyphs are drawn inside a 16×16 box with its origin at (0,0) and translated into place by
 * {@link iconFor}, because an SVG path cannot be offset by an attribute — and the width is known
 * anyway at the moment the element is written into the graph.
 */
const GLYPHS: Array<[test: (concept: string) => boolean, path: string]> = [
  // Component: the classic box with two tabs on its left edge.
  [(c) => c.endsWith('Component'), 'M4 1h11v14H4V1zM0 4h6v3H0zM0 9h6v3H0z'],
  // Interface: the lollipop.
  [(c) => c.endsWith('Interface'), 'M0 8h7M7 3a5 5 0 1 1 0 10a5 5 0 1 1 0-10z'],
  // Collaboration: two overlapping circles.
  [(c) => c.endsWith('Collaboration'), 'M5 8a4.5 4.5 0 1 1 0 .01zM11 8a4.5 4.5 0 1 1 0 .01z'],
  // Actor: a person.
  [(c) => c.endsWith('Actor'), 'M8 1a2.4 2.4 0 1 1 0 .01zM8 5v6M4 7h8M8 11l-3 4M8 11l3 4'],
  // Role: a cylinder on its side.
  [(c) => c.endsWith('Role'), 'M3 4h9a4 4 0 0 1 0 8H3V4zM12 4a4 4 0 0 0 0 8'],
  // Process: an arrow pointing right.
  [(c) => c.endsWith('Process'), 'M0 5h9V2l7 6-7 6v-3H0V5z'],
  // Function: an arrow pointing up.
  [(c) => c.endsWith('Function'), 'M8 0l8 16H0L8 0z'],
  // Interaction: two halves of a rounded shape.
  [(c) => c.endsWith('Interaction'), 'M7 2a6 6 0 0 0 0 12V2zM9 2a6 6 0 0 1 0 12V2z'],
  // Event: a pennant with a notched tail.
  [(c) => c.endsWith('Event'), 'M0 3h11l5 5-5 5H0l4-5-4-5z'],
  // Service: the pill, the same shape as its body.
  [(c) => c.endsWith('Service'), 'M5 3h6a5 5 0 0 1 0 10H5a5 5 0 0 1 0-10z'],
  // Artifact: a document with a folded corner.
  [(c) => c === 'Artifact', 'M2 0h8l4 4v12H2V0zM10 0v4h4'],
  // Node: an isometric box.
  [(c) => c === 'Node', 'M0 5h11v10H0V5zM0 5l4-4h11v10l-4 4M11 5l4-4'],
  // Device: a screen on a stand.
  [(c) => c === 'Device', 'M1 2h14v9H1V2zM0 14h16l-2-3H2l-2 3z'],
  // System software: a circle with a following arc.
  [(c) => c === 'SystemSoftware', 'M9 8a5.5 5.5 0 1 1 0 .01zM3 3a6.5 6.5 0 0 0 0 10'],
  // Network and path: a link between two points.
  [(c) => c === 'CommunicationNetwork' || c === 'Path', 'M2 8a2 2 0 1 1 0 .01zM14 8a2 2 0 1 1 0 .01zM4 8h8'],
  // Passive structure: a box with a bar across the top.
  [
    (c) => c.endsWith('Object') || c === 'Contract' || c === 'Representation' || c === 'Deliverable',
    'M0 2h16v12H0V2zM0 6h16',
  ],
]

/**
 * The glyph for a concept, or an empty path when it has none yet.
 *
 * The path is never rewritten to move it — it is drawn at the origin and put in place with a
 * transform. Shifting path data by hand means parsing it, and `H`/`V`/`a` do not take pairs of
 * coordinates, so the obvious regular expression quietly mangles half the glyphs.
 */
export function iconFor(concept: string): string {
  return GLYPHS.find(([test]) => test(concept))?.[1] ?? ''
}

/** Top-right corner, clear of the type caption which is centred. */
export function iconPlacement(width: number): string {
  return `translate(${Math.round(width - 26)}, 8)`
}
