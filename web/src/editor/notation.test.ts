import { describe, expect, it } from 'vitest'

import { NOTATION, notationFor } from './notation.ts'

/**
 * The eleven ArchiMate relationship types, and how their lines are drawn.
 *
 * Two of these checks are about defects this project has already shipped once, under a different
 * canvas library: a marker that was neither a shape nor an explicit "none" rendered as a reference
 * to nothing — the line still drew, so the diagram merely looked plain — and colours baked into a
 * marker made selection and the dark theme impossible.
 */
const TYPES = [
  'Composition',
  'Aggregation',
  'Assignment',
  'Realization',
  'Serving',
  'Access',
  'Influence',
  'Triggering',
  'Flow',
  'Specialization',
  'Association',
]

describe('notationFor', () => {
  it('has notation for every relationship type in the language', () => {
    for (const type of TYPES) {
      expect(NOTATION[type], type).toBeDefined()
    }
    expect(Object.keys(NOTATION).sort()).toEqual([...TYPES].sort())
  })

  it('answers for an unknown type instead of throwing', () => {
    // A profile or a future language version can name something this table has never heard of.
    // A plain line is the right answer; an exception in the middle of a render is not.
    expect(notationFor('Teleportation')).toEqual({})
  })

  it('never carries a colour', () => {
    // Colour comes from CSS so the dark theme keeps working and a selected line tints its own
    // arrowhead. A marker that names its own fill or stroke defeats both.
    for (const [type, notation] of Object.entries(NOTATION)) {
      for (const marker of [notation.markerStart, notation.markerEnd]) {
        if (!marker) {
          continue
        }
        expect(Object.keys(marker), type).not.toContain('fill')
        expect(Object.keys(marker), type).not.toContain('stroke')
        expect(marker.class, type).toMatch(/^ag-marker-/)
      }
    }
  })

  it('gives every marker a shape JointJS can draw', () => {
    for (const [type, notation] of Object.entries(NOTATION)) {
      for (const marker of [notation.markerStart, notation.markerEnd]) {
        if (!marker) {
          continue
        }
        expect(marker.type, type).toMatch(/^(path|circle)$/)
        expect(marker.type === 'path' ? marker.d : marker.r, type).toBeDefined()
      }
    }
  })

  it('puts the diamond at the whole, where ArchiMate puts it', () => {
    // Composition and aggregation are the two that mark their *source*. Drawing the diamond at the
    // part says the part contains the whole, which is a different and false statement.
    expect(notationFor('Composition').markerStart).toBeDefined()
    expect(notationFor('Composition').markerEnd).toBeUndefined()
    expect(notationFor('Aggregation').markerStart).toBeDefined()
    expect(notationFor('Serving').markerStart).toBeUndefined()
  })

  it('dashes the relationships the notation draws dashed, and only those', () => {
    const dashed = Object.entries(NOTATION)
      .filter(([, notation]) => notation.dash)
      .map(([type]) => type)
      .sort()
    expect(dashed).toEqual(['Access', 'Flow', 'Influence', 'Realization'])
  })
})
