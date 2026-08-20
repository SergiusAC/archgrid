import { describe, expect, it } from 'vitest'

import type { Command, ModelElement, ModelRelationship } from '../api/types.ts'
import { invert } from './store.ts'

/**
 * Undo, built as commands rather than as a snapshot.
 *
 * Every branch here is a chance to lose data quietly: an inverse that is wrong does not fail, it
 * applies — and the architect finds out later that undoing a deletion brought the element back
 * without its relationships. That is the branch this file exists for; the rest are cheap to keep
 * honest while we are here.
 */
const element = (key: string, over: Partial<ModelElement> = {}): ModelElement => ({
  key,
  type: 'ApplicationComponent',
  conceptType: 'ApplicationComponent',
  profile: null,
  name: key,
  documentation: null,
  properties: {},
  ...over,
})

const relationship = (key: string, source: string, target: string, over: Partial<ModelRelationship> = {}): ModelRelationship => ({
  key,
  type: 'Serving',
  source,
  target,
  name: null,
  properties: {},
  ...over,
})

const before = {
  elements: {
    ledger: element('ledger', {
      name: 'Ledger',
      documentation: 'The book of record',
      properties: { owner: 'payments', tag: 'legacy' },
    }),
    api: element('api', { name: 'Payment API' }),
  },
  relationships: {
    'api-serves-ledger': relationship('api-serves-ledger', 'api', 'ledger', { name: 'writes to' }),
    'ledger-flows-api': relationship('ledger-flows-api', 'ledger', 'api', { type: 'Flow' }),
    elsewhere: relationship('elsewhere', 'api', 'api'),
  },
}

const onView = { key: 'context', elements: ['ledger', 'api'] }
const undo = (...commands: Command[]) => invert(commands, { ...before, view: onView })

describe('invert', () => {
  it('undoes a creation by deleting it', () => {
    expect(undo({ op: 'create-element', key: 'new', type: 'ApplicationComponent', name: 'New' })).toEqual([
      { op: 'delete-element', key: 'new' },
    ])
  })

  it('undoes a rename with the name that was there', () => {
    expect(undo({ op: 'rename-element', key: 'ledger', name: 'General Ledger' })).toEqual([
      { op: 'rename-element', key: 'ledger', name: 'Ledger' },
    ])
  })

  it('undoes documentation, including back to nothing', () => {
    expect(undo({ op: 'set-element-documentation', key: 'api', documentation: 'written' })).toEqual([
      { op: 'set-element-documentation', key: 'api', documentation: null },
    ])
  })

  it('undoes a property by restoring its old value, or removing it', () => {
    expect(undo({ op: 'set-element-property', key: 'ledger', property: 'owner', value: 'billing' })).toEqual([
      { op: 'set-element-property', key: 'ledger', property: 'owner', value: 'payments' },
    ])
    // A property that did not exist is undone by removal — `null` is how the command surface says
    // "remove", and leaving it out would keep a value the user never set.
    expect(undo({ op: 'set-element-property', key: 'ledger', property: 'sla', value: '99.9' })).toEqual([
      { op: 'set-element-property', key: 'ledger', property: 'sla', value: null },
    ])
  })

  it('undoes a deletion by putting back the element and every relationship it took with it', () => {
    const inverse = undo({ op: 'delete-element', key: 'ledger' })

    expect(inverse[0]).toEqual({
      op: 'create-element',
      key: 'ledger',
      type: 'ApplicationComponent',
      name: 'Ledger',
      documentation: 'The book of record',
    })
    // Back onto the diagram it was deleted from, or the element returns to the repository and the
    // canvas does not change — which reads as "undo did nothing".
    expect(inverse[1]).toEqual({ op: 'add-to-view', key: 'context', element: 'ledger' })
    // Both relationships that touched it, either way round; the third, which did not, stays out.
    expect(inverse.slice(2)).toEqual([
      {
        op: 'add-relationship',
        key: 'api-serves-ledger',
        type: 'Serving',
        source: 'api',
        target: 'ledger',
        name: 'writes to',
      },
      { op: 'add-relationship', key: 'ledger-flows-api', type: 'Flow', source: 'ledger', target: 'api' },
    ])
  })

  it('undoes a relationship deletion with its type, ends and name', () => {
    expect(undo({ op: 'delete-relationship', key: 'api-serves-ledger' })).toEqual([
      {
        op: 'add-relationship',
        key: 'api-serves-ledger',
        type: 'Serving',
        source: 'api',
        target: 'ledger',
        name: 'writes to',
      },
    ])
  })

  it('undoes membership both ways', () => {
    expect(undo({ op: 'add-to-view', key: 'context', element: 'ledger' })).toEqual([
      { op: 'remove-from-view', key: 'context', element: 'ledger' },
    ])
    expect(undo({ op: 'remove-from-view', key: 'context', element: 'ledger' })).toEqual([
      { op: 'add-to-view', key: 'context', element: 'ledger' },
    ])
  })

  it('undoes creating a view by deleting it', () => {
    expect(undo({ op: 'create-view', key: 'context', name: 'Context', viewpoint: 'layered' })).toEqual([
      { op: 'delete-view', key: 'context' },
    ])
  })

  it('offers nothing where it cannot know the previous state', () => {
    // A wrong inverse is worse than none: it applies, and the user finds out from the diff.
    expect(
      undo(
        { op: 'set-viewpoint', key: 'context', viewpoint: 'application' },
        { op: 'set-view-filters', key: 'context', filters: [] },
        { op: 'rename-view', key: 'context', name: 'Renamed' },
        { op: 'delete-view', key: 'context' },
      ),
    ).toEqual([])
  })

  it('does not put an element back on a view that was not showing it', () => {
    const inverse = invert([{ op: 'delete-element', key: 'ledger' }], {
      ...before,
      view: { key: 'context', elements: ['api'] },
    })
    expect(inverse.some((command) => command.op === 'add-to-view')).toBe(false)
  })

  it('offers nothing for an element the snapshot does not hold', () => {
    // The snapshot is read at the moment the command is issued; a stale one must produce no
    // inverse rather than one that names an element with no properties.
    expect(undo({ op: 'delete-element', key: 'vanished' })).toEqual([])
    expect(undo({ op: 'rename-element', key: 'vanished', name: 'x' })).toEqual([])
  })

  it('undoes a batch in reverse, because order is what makes it apply', () => {
    const inverse = undo(
      { op: 'create-element', key: 'new', type: 'ApplicationComponent', name: 'New' },
      { op: 'add-relationship', key: 'new-serves', type: 'Serving', source: 'new', target: 'ledger' },
    )

    // The relationship goes first: deleting the element first would take the relationship with it
    // and leave a command referring to something that no longer exists.
    expect(inverse).toEqual([
      { op: 'delete-relationship', key: 'new-serves' },
      { op: 'delete-element', key: 'new' },
    ])
  })
})
