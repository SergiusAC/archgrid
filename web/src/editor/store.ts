import { create } from 'zustand'

import type { Command, ModelElement, ModelRelationship } from '../api/types.ts'

/**
 * There is deliberately no model store here.
 *
 * The semantic model is server state: the server owns it, validates it and versions it, and
 * TanStack Query already caches and invalidates it. Mirroring it into a second store would
 * create exactly the "two sources of truth that drift" problem ADR-0003 exists to avoid — one
 * of them would go stale after a rejected command and nobody would notice until a diagram
 * disagreed with the database.
 *
 * What lives below is client state only: where the boxes are, and what the user did. Neither
 * of those is the server's business, and both are lost on reload without consequence.
 */
export interface Position {
  x: number
  y: number
}

/**
 * Layout state: coordinates, nothing else.
 *
 * Client-only for now. Persisting layout needs the view tables, which arrive with views in
 * phase 4; until then a reload re-runs auto-layout. Deliberately not smuggled into the model
 * to avoid that, because doing so would put box positions into the commit log.
 */
interface LayoutState {
  positions: Record<string, Position>
  /**
   * Elements hidden from the current diagram but still present in the model.
   *
   * This is view membership, which ADR-0003 makes a separate layer from the model: removing an
   * element from a diagram is not a model change and must never look like one. Real view
   * definitions are versioned server-side in phase 4; until then membership lives here, next to
   * the coordinates, because both are view concerns and both are lost on reload harmlessly.
   */
  hidden: Record<string, true>
  setPosition: (key: string, position: Position) => void
  setPositions: (positions: Record<string, Position>) => void
  hide: (keys: string[]) => void
  show: (key: string) => void
  showAll: () => void
}

export const useLayoutStore = create<LayoutState>()((set) => ({
  positions: {},
  hidden: {},
  setPosition: (key, position) => set((state) => ({ positions: { ...state.positions, [key]: position } })),
  setPositions: (positions) => set((state) => ({ positions: { ...state.positions, ...positions } })),
  hide: (keys) =>
    set((state) => ({
      hidden: { ...state.hidden, ...Object.fromEntries(keys.map((key) => [key, true as const])) },
    })),
  show: (key) =>
    set((state) => {
      const { [key]: _removed, ...rest } = state.hidden
      return { hidden: rest }
    }),
  showAll: () => set({ hidden: {} }),
}))

/**
 * Undo/redo as a stack of command batches, not of state snapshots.
 *
 * Undo applies the inverse commands and therefore produces a new commit rather than rewriting
 * history. That is the honest behaviour for a versioned model — history is a record of what
 * happened, and "I changed my mind" is something that happened.
 */
interface HistoryState {
  undoStack: { forward: Command[]; inverse: Command[]; label: string }[]
  redoStack: { forward: Command[]; inverse: Command[]; label: string }[]
  push: (entry: { forward: Command[]; inverse: Command[]; label: string }) => void
  popUndo: () => { forward: Command[]; inverse: Command[]; label: string } | undefined
  popRedo: () => { forward: Command[]; inverse: Command[]; label: string } | undefined
  pushRedo: (entry: { forward: Command[]; inverse: Command[]; label: string }) => void
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

/**
 * Builds the commands that undo a batch, using the state as it was before the batch ran.
 *
 * Order is reversed: undoing "create A, then put B inside A" has to remove B from A before A
 * can go.
 */
export function invert(
  commands: Command[],
  before: { elements: Record<string, ModelElement>; relationships: Record<string, ModelRelationship> },
): Command[] {
  const inverse: Command[] = []

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
      case 'move-element': {
        const previous = before.elements[command.key]
        if (previous) {
          inverse.push({ op: 'move-element', key: command.key, parent: previous.parent ?? undefined })
        }
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
            parent: previous.parent ?? undefined,
          })
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
    }
  }

  return inverse.reverse()
}
