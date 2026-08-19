import type { C4Projection } from '../api/types.ts'
import { NEST_HEADER, NEST_PADDING, NODE_HEIGHT, NODE_WIDTH } from './layout.ts'
import type { Position } from './store.ts'

/**
 * A C4 diagram arranged the way C4 diagrams are drawn.
 *
 * ELK is the right tool for a graph of peers and the wrong one here: a C4 view has a subject, and
 * the subject is a boundary with its parts inside it and everyone else around it. Handing that to
 * a layered algorithm produces a correct graph drawing in which nothing is obviously the point.
 *
 * So the focus becomes a container sized to hold its children, and the neighbours are placed above
 * and below it — the ones that use it and the ones it uses are not distinguished here, because
 * that would need the relationship directions and would be a guess as often as not.
 */

/** Two columns inside the boundary: wider than one, and still readable on a laptop. */
const COLUMNS = 2

const GAP = 40

export interface C4Layout {
  positions: Record<string, Position>
  sizes: Record<string, { width: number; height: number }>
  parents: Record<string, string | null>
}

export function c4Layout(projection: C4Projection): C4Layout {
  const shown = new Set(projection.elements.map((element) => element.key))
  // What the projection says is inside the focus: a Composition between two shown elements. Read
  // from the relationships rather than assumed, because at the component level the children are
  // one step further down (ADR-0011 — containment is a relationship like any other).
  const parents: Record<string, string | null> = {}
  for (const relationship of projection.relationships) {
    if (relationship.type === 'Composition' && shown.has(relationship.source) && shown.has(relationship.target)) {
      parents[relationship.target] = relationship.source
    }
  }

  const children = projection.elements
    .map((element) => element.key)
    .filter((key) => parents[key] === projection.focus)
  const others = projection.elements
    .map((element) => element.key)
    .filter((key) => key !== projection.focus && parents[key] !== projection.focus)

  const rows = Math.max(1, Math.ceil(children.length / COLUMNS))
  const focusWidth =
    children.length > 0
      ? COLUMNS * NODE_WIDTH + (COLUMNS + 1) * NEST_PADDING
      : NODE_WIDTH * 1.6
  const focusHeight =
    children.length > 0
      ? NEST_HEADER + rows * NODE_HEIGHT + (rows + 1) * NEST_PADDING
      : NODE_HEIGHT * 1.4

  // Neighbours in a band above the boundary, wrapping when there are many of them.
  const perRow = Math.max(2, Math.ceil(Math.sqrt(others.length)) + 1)
  const bandRows = Math.ceil(others.length / perRow)
  const bandHeight = bandRows > 0 ? bandRows * (NODE_HEIGHT + GAP) : 0
  const bandWidth = perRow * (NODE_WIDTH + GAP) - GAP

  const width = Math.max(focusWidth, bandWidth)
  const originX = 60
  const originY = 60

  const positions: Record<string, Position> = {}
  const sizes: Record<string, { width: number; height: number }> = {}

  others.forEach((key, index) => {
    const row = Math.floor(index / perRow)
    const column = index % perRow
    const inRow = Math.min(perRow, others.length - row * perRow)
    const rowWidth = inRow * (NODE_WIDTH + GAP) - GAP
    positions[key] = {
      x: snap(originX + (width - rowWidth) / 2 + column * (NODE_WIDTH + GAP)),
      y: snap(originY + row * (NODE_HEIGHT + GAP)),
    }
  })

  const focusX = snap(originX + (width - focusWidth) / 2)
  const focusY = snap(originY + bandHeight + GAP)
  positions[projection.focus] = { x: focusX, y: focusY }
  sizes[projection.focus] = { width: focusWidth, height: focusHeight }

  children.forEach((key, index) => {
    const row = Math.floor(index / COLUMNS)
    const column = index % COLUMNS
    positions[key] = {
      x: snap(focusX + NEST_PADDING + column * (NODE_WIDTH + NEST_PADDING)),
      y: snap(focusY + NEST_HEADER + NEST_PADDING + row * (NODE_HEIGHT + NEST_PADDING)),
    }
  })

  // Only the focus's own children are nested. Anything deeper is a neighbour's business and is
  // drawn flat, which is what "roll a neighbour up to its system" means on the canvas too.
  const nesting: Record<string, string | null> = {}
  for (const key of children) {
    nesting[key] = projection.focus
  }
  return { positions, sizes, parents: nesting }
}

/** The one grid everything steps by, so a projected diagram lines up with a drawn one. */
function snap(value: number): number {
  return Math.round(value / 10) * 10
}
