import { describe, expect, it } from 'vitest'

import type { MetamodelDto } from '../api/types.ts'
import { Metamodel } from './Metamodel.ts'

/**
 * The client's copy of the language, and the one place a parsing slip is expensive.
 *
 * The matrix travels in the letter encoding the metamodel file uses — `"cfgorstv"` means eight
 * permitted relationship types — because a fully expanded form would be ten times the payload. The
 * cost of that is exactly this: a decoder, and a decoder is code that can be wrong. Wrong here and
 * the editor teaches the notation incorrectly, offering a relationship the server will refuse or
 * hiding one it would have allowed. The server re-checks every command (ADR-0004), so a slip
 * cannot corrupt a model — it can only make the product lie to the architect while it draws.
 */
const dto: MetamodelDto = {
  language: 'archimate',
  version: '3.2',
  elementTypes: [
    { concept: 'ApplicationComponent', name: 'Application Component', layer: 'application', aspect: 'active-structure', c4Shape: 'box' },
    { concept: 'ApplicationService', name: 'Application Service', layer: 'application', aspect: 'behavior', c4Shape: 'omitted' },
    { concept: 'BusinessActor', name: 'Business Actor', layer: 'business', aspect: 'active-structure', c4Shape: 'person' },
    { concept: 'Node', name: 'Node', layer: 'technology', aspect: 'active-structure', c4Shape: 'box' },
    // Matrix endpoints rather than elements: they exist in the language's table and never in a
    // palette, and leaving them in is how a palette gains two entries nobody can draw.
    { concept: 'Junction', name: 'Junction', layer: 'other', aspect: 'none', c4Shape: 'omitted' },
    { concept: 'Relationship', name: 'Relationship', layer: 'other', aspect: 'none', c4Shape: 'omitted' },
  ],
  relationshipTypes: [
    { concept: 'Composition', name: 'Composition', key: 'c', category: 'structural', strength: 1 },
    { concept: 'Serving', name: 'Serving', key: 'v', category: 'dependency', strength: 0 },
    { concept: 'Flow', name: 'Flow', key: 'f', category: 'dynamic', strength: 0 },
    { concept: 'Association', name: 'Association', key: 'o', category: 'other', strength: 0 },
  ],
  viewpoints: [
    { code: 'application', name: 'Application', concepts: ['ApplicationComponent', 'ApplicationService'] },
    // An empty palette upstream means every concept is allowed — that is the Layered viewpoint,
    // and reading it as "nothing is allowed" produces an empty palette instead of the full one.
    { code: 'layered', name: 'Layered', concepts: [] },
  ],
  c4Levels: [{ code: 'context', name: 'System Context', depth: 0 }],
  permitted: {
    ApplicationComponent: {
      ApplicationComponent: 'cfov',
      ApplicationService: 'ov',
      Node: '',
    },
  },
}

const metamodel = new Metamodel(dto)

describe('permittedBetween', () => {
  it('decodes the letters into types, in the order the metamodel declares them', () => {
    expect(metamodel.permittedBetween('ApplicationComponent', 'ApplicationComponent').map((t) => t.concept)).toEqual([
      'Composition',
      'Flow',
      'Association',
      'Serving',
    ])
  })

  it('answers nothing where the pair is absent, empty or unknown', () => {
    expect(metamodel.permittedBetween('ApplicationComponent', 'Node')).toEqual([])
    expect(metamodel.permittedBetween('ApplicationService', 'ApplicationComponent')).toEqual([])
    expect(metamodel.permittedBetween('Nonsense', 'ApplicationComponent')).toEqual([])
  })
})

describe('permits', () => {
  it('reads one letter out of the cell', () => {
    expect(metamodel.permits('ApplicationComponent', 'ApplicationComponent', 'Composition')).toBe(true)
    expect(metamodel.permits('ApplicationComponent', 'ApplicationService', 'Serving')).toBe(true)
  })

  it('refuses what the cell does not carry', () => {
    expect(metamodel.permits('ApplicationComponent', 'ApplicationService', 'Composition')).toBe(false)
    expect(metamodel.permits('ApplicationComponent', 'Node', 'Serving')).toBe(false)
  })

  it('refuses rather than guesses when the type is not in the language', () => {
    expect(metamodel.permits('ApplicationComponent', 'ApplicationComponent', 'Teleportation')).toBe(false)
  })

  it('is directional', () => {
    // Serving from component to service, and nothing back: the matrix is not symmetric, and
    // treating it as symmetric is how a tool ends up drawing dependencies backwards.
    expect(metamodel.permits('ApplicationComponent', 'ApplicationService', 'Serving')).toBe(true)
    expect(metamodel.permits('ApplicationService', 'ApplicationComponent', 'Serving')).toBe(false)
  })
})

describe('explain', () => {
  it('names the elements and what is allowed instead', () => {
    expect(metamodel.explain('ApplicationComponent', 'ApplicationService', 'Composition')).toBe(
      'Composition is not permitted from Application Component to Application Service. ' +
        'Permitted instead: Association, Serving.',
    )
  })

  it('says so plainly when nothing at all is allowed', () => {
    expect(metamodel.explain('ApplicationComponent', 'Node', 'Serving')).toBe(
      'Serving is not permitted from Application Component to Node. No relationship type is permitted between these two.',
    )
  })

  it('confirms a permitted relationship in the same voice', () => {
    expect(metamodel.explain('ApplicationComponent', 'ApplicationComponent', 'Flow')).toBe(
      'Flow from Application Component to Application Component is permitted.',
    )
  })

  it('falls back to the raw concept when a name is unknown', () => {
    expect(metamodel.explain('Nonsense', 'ApplicationComponent', 'Serving')).toContain('Nonsense')
  })
})

describe('palette', () => {
  it('offers what the viewpoint names', () => {
    expect(metamodel.palette('application').map((t) => t.concept)).toEqual([
      'ApplicationComponent',
      'ApplicationService',
    ])
  })

  it('reads an empty viewpoint as "everything", which is what Layered means', () => {
    expect(metamodel.palette('layered').map((t) => t.concept)).toEqual([
      'BusinessActor',
      'ApplicationComponent',
      'ApplicationService',
      'Node',
    ])
  })

  it('never offers the matrix pseudo-concepts', () => {
    for (const code of ['application', 'layered', 'nonexistent']) {
      const concepts = metamodel.palette(code).map((t) => t.concept)
      expect(concepts).not.toContain('Junction')
      expect(concepts).not.toContain('Relationship')
    }
  })

  it('orders by layer as ArchiMate draws it, then by name', () => {
    // Business above application above technology; an unknown viewpoint behaves as Layered does.
    expect(metamodel.palette('nonexistent').map((t) => t.layer)).toEqual([
      'business',
      'application',
      'application',
      'technology',
    ])
  })
})
