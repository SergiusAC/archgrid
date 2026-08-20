import { create } from 'zustand'

import type { Command, ModelElement, ModelRelationship } from '../api/types.ts'

/**
 * There is deliberately no model store here.
 *
 * The semantic model is server state: the server owns it, validates it and versions it, and
 * TanStack Query already caches and invalidates it. Mirroring it into a second store would
 * create exactly the "two sources of truth that drift" problem ADR-0003 exists to avoid.
 *
 * What lives here is client state only: what the user did, so it can be undone.
 */

export interface Position {
  x: number
  y: number
}

/**
 * One undoable action, of either kind.
 *
 * Two kinds and one stack, deliberately. Model changes are commands and produce commits;
 * moving a box is layout and produces none (ADR-0003). But the user does not think in layers —
 * they think "undo what I just did". A stack that only knew about commands answered Ctrl+Z
 * after a drag by undoing a rename from five minutes earlier, which is how people learn to
 * stop trusting undo altogether.
 */
export type UndoEntry =
  | { kind: 'commands'; forward: Command[]; inverse: Command[]; label: string }
  | {
      kind: 'layout'
      view: string
      before: Record<string, Position>
      after: Record<string, Position>
      label: string
    }

interface HistoryState {
  undoStack: UndoEntry[]
  redoStack: UndoEntry[]
  push: (entry: UndoEntry) => void
  popUndo: () => UndoEntry | undefined
  popRedo: () => UndoEntry | undefined
  pushRedo: (entry: UndoEntry) => void
  clear: () => void
}

export const useHistoryStore = create<HistoryState>()((set, get) => ({
  undoStack: [],
  redoStack: [],
  // A new action invalidates the redo branch, exactly as an editor is expected to behave.
  push: (entry) => set((state) => ({ undoStack: [...state.undoStack, entry], redoStack: [] })),
  popUndo: () => {
    const stack = get().undoStack
    const entry = stack.at(-1)
    if (entry) {
      set({ undoStack: stack.slice(0, -1) })
    }
    return entry
  },
  popRedo: () => {
    const stack = get().redoStack
    const entry = stack.at(-1)
    if (entry) {
      set({ redoStack: stack.slice(0, -1) })
    }
    return entry
  },
  pushRedo: (entry) => set((state) => ({ redoStack: [...state.redoStack, entry] })),
  clear: () => set({ undoStack: [], redoStack: [] }),
}))

/** A short description of what Ctrl+Z would undo, so the button can say so. */
export function describeEntry(entry: UndoEntry | undefined): string {
  return entry ? entry.label : ''
}

/**
 * Builds the commands that undo a batch, using the state as it was before the batch ran.
 *
 * Order is reversed: undoing "create A, then put B inside A" has to remove B from A before A
 * can go.
 */
/**
 * @param before the model as it was when the command was issued. `view` is the diagram the user is
 *     looking at and what it showed: deleting an element takes it off every view, and undo has to
 *     put it back somewhere or the user watches nothing happen. Only that one view is restored —
 *     the client does not know which others showed it, and phase 5's commit diff will.
 */
export function invert(
  commands: Command[],
  before: {
    elements: Record<string, ModelElement>
    relationships: Record<string, ModelRelationship>
    view?: { key: string; elements: Iterable<string> } | null
  },
): Command[] {
  /*
   * One entry per command, not one flat list.
   *
   * A batch is undone in reverse — the last thing done is the first thing taken back — but the
   * commands that undo a *single* command have an order of their own that must survive that
   * reversal. Undoing the deletion of an element means creating it and then re-adding the
   * relationships that went with it; flattening first put those relationships ahead of the element
   * they refer to, and the server refused the whole batch as a dangling endpoint. Which meant undo
   * of a delete simply did not work for any element anybody had connected to anything.
   */
  const steps: Command[][] = []
  const inverse: Command[] = []
  const step = () => {
    if (inverse.length > 0) {
      steps.push(inverse.splice(0, inverse.length))
    }
  }

  for (const command of commands) {
    switch (command.op) {
      case 'create-element':
        inverse.push({ op: 'delete-element', key: command.key })
        break
      case 'add-relationship':
        inverse.push({ op: 'delete-relationship', key: command.key })
        break
      case 'rename-element': {
        const previous = before.elements[command.key]
        if (previous) {
          inverse.push({ op: 'rename-element', key: command.key, name: previous.name })
        }
        break
      }
      case 'set-element-documentation': {
        const previous = before.elements[command.key]
        if (previous) {
          inverse.push({
            op: 'set-element-documentation',
            key: command.key,
            documentation: previous.documentation,
          })
        }
        break
      }
      case 'set-element-property': {
        const previous = before.elements[command.key]
        inverse.push({
          op: 'set-element-property',
          key: command.key,
          property: command.property,
          value: previous?.properties[command.property] ?? null,
        })
        break
      }
      case 'delete-element': {
        const previous = before.elements[command.key]
        if (previous) {
          inverse.push({
            op: 'create-element',
            key: previous.key,
            type: previous.type,
            name: previous.name,
            documentation: previous.documentation ?? undefined,
          })
          // Back onto the diagram it was deleted from. Without this the element returns to the
          // repository and the canvas does not change, which reads as "undo did nothing" — and
          // the relationships below would have nowhere to be drawn either.
          if (before.view && [...before.view.elements].includes(command.key)) {
            inverse.push({ op: 'add-to-view', key: before.view.key, element: command.key })
          }
          // Deleting an element takes its relationships with it, so undoing has to put them back.
          for (const relationship of Object.values(before.relationships)) {
            if (relationship.source === command.key || relationship.target === command.key) {
              inverse.push({
                op: 'add-relationship',
                key: relationship.key,
                type: relationship.type,
                source: relationship.source,
                target: relationship.target,
                name: relationship.name ?? undefined,
              })
            }
          }
        }
        break
      }
      case 'delete-relationship': {
        const previous = before.relationships[command.key]
        if (previous) {
          inverse.push({
            op: 'add-relationship',
            key: previous.key,
            type: previous.type,
            source: previous.source,
            target: previous.target,
            name: previous.name ?? undefined,
          })
        }
        break
      }
      case 'create-view':
        inverse.push({ op: 'delete-view', key: command.key })
        break
      case 'add-to-view':
        inverse.push({ op: 'remove-from-view', key: command.key, element: command.element })
        break
      case 'remove-from-view':
        inverse.push({ op: 'add-to-view', key: command.key, element: command.element })
        break
      case 'rename-view':
      case 'set-viewpoint':
      case 'set-view-filters':
        // Needs the view's previous state, which this snapshot does not carry. Arrives with the
        // commit diff in phase 5; a wrong inverse would be worse than none.
        break
      case 'delete-view':
        break
    }
    step()
  }

  return steps.reverse().flat()
}
