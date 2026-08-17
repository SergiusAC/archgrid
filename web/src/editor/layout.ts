import type { ELK as ElkInstance } from 'elkjs/lib/elk-api.js'

import type { ModelElement, ModelRelationship } from '../api/types.ts'
import type { Position } from './store.ts'

/**
 * ELK is ~1.3 MB and is not needed to paint the first frame — layout only runs when a model
 * loads with unplaced elements or when the user asks for it. Loading it on demand keeps it out
 * of the initial bundle, which matters more here than usual: an on-premise install is often
 * reached over a slow internal link.
 */
let elkPromise: Promise<ElkInstance> | null = null

function elk(): Promise<ElkInstance> {
  elkPromise ??= import('elkjs/lib/elk.bundled.js').then((module) => new module.default())
  return elkPromise
}

export const NODE_WIDTH = 180
export const NODE_HEIGHT = 80

/**
 * Auto-layout with ELK's layered algorithm.
 *
 * Layered rather than force-directed because architecture diagrams are read as flows between
 * ranks, and orthogonal edges are what ArchiMate and C4 both look like. `dagre` produces
 * something similar but handles nested containers poorly, and containment is a first-class
 * relationship here.
 */
export async function autoLayout(
  elements: ModelElement[],
  relationships: ModelRelationship[],
): Promise<Record<string, Position>> {
  if (elements.length === 0) {
    return {}
  }

  const graph = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.layered.spacing.nodeNodeBetweenLayers': '90',
      'elk.spacing.nodeNode': '48',
      'elk.padding': '[top=32,left=32,bottom=32,right=32]',
    },
    children: elements.map((element) => ({
      id: element.key,
      width: NODE_WIDTH,
      height: NODE_HEIGHT,
    })),
    edges: relationships
      // ELK rejects an edge whose endpoints it has not been given; a relationship can only
      // point at elements in the same model, so this is belt and braces after a partial load.
      .filter((relationship) =>
        elements.some((element) => element.key === relationship.source) &&
        elements.some((element) => element.key === relationship.target),
      )
      .map((relationship) => ({
        id: relationship.key,
        sources: [relationship.source],
        targets: [relationship.target],
      })),
  }

  const laid = await (await elk()).layout(graph)
  const positions: Record<string, Position> = {}
  for (const child of laid.children ?? []) {
    positions[child.id] = { x: child.x ?? 0, y: child.y ?? 0 }
  }
  return positions
}

/**
 * Somewhere sensible to drop a newly created element when nothing else says where.
 *
 * Scans a grid for a genuinely empty slot rather than indexing by how many elements exist.
 * The counting version put new elements straight on top of ones ELK had already placed
 * somewhere else — a diagram that silently hides a box behind another is worse than an untidy
 * one, because the element looks like it was never created.
 */
export function nextFreePosition(taken: Record<string, Position>): Position {
  const occupied = Object.values(taken)
  const stepX = NODE_WIDTH + 48
  const stepY = NODE_HEIGHT + 48
  const columns = 8

  for (let row = 0; row < 500; row++) {
    for (let column = 0; column < columns; column++) {
      const candidate = { x: 40 + column * stepX, y: 40 + row * stepY }
      const clashes = occupied.some(
        (position) =>
          Math.abs(position.x - candidate.x) < NODE_WIDTH + 16 &&
          Math.abs(position.y - candidate.y) < NODE_HEIGHT + 16,
      )
      if (!clashes) {
        return candidate
      }
    }
  }
  // Unreachable for any plausible diagram; better than looping forever.
  return { x: 40, y: 40 + 500 * stepY }
}
