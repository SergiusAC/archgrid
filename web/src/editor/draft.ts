import type { ConnectionRouter } from '../api/types.ts'
import type { Position } from './store.ts'

/**
 * The layout of a view as the user has it, before they decide to keep it.
 *
 * Layout used to be written to the server a few hundred milliseconds after every drag. That is
 * invisible — nothing tells you whether your arrangement is safe — and it is the origin of every
 * ordering trap this editor has hit: a save in flight racing a refetch, a debounce landing after
 * the change it was meant to record, an arrangement written over the real one.
 *
 * So layout now behaves like a document: edited freely, kept in the browser so it survives a
 * reload or a closed laptop, and written to the server when the user says to.
 *
 * Model changes are not drafted. A command is validated against the metamodel, attributed and
 * committed (ADR-0004); holding one back would mean finding out only at save time that it was
 * never allowed, which is the opposite of what the pipeline is for.
 */
export interface LayoutDraft {
  version: 2
  /**
     * Which element each placement draws, by placement id.
     *
     * The draft is keyed by placement rather than by element, because a view may draw the same
     * element twice and a copy the user has just made exists only here until they save.
     */
  placements: Record<string, string>
  positions: Record<string, Position>
  sizes: Record<string, { width: number; height: number }>
  parents: Record<string, string | null>
  /** Which element is drawn over which — presentation, exactly like the coordinates. */
  order: Record<string, number>
  /** Relationships this view declines to draw. Also presentation (ADR-0011). */
  hidden: string[]
  /** Where each line bends, by line id. Presentation as much as the coordinates are. */
  vertices: Record<string, { x: number; y: number }[]>
  /** The lines of this view: which relationship each stands for, and its chosen ends (V15). */
  lines: Record<string, { key: string; source: string | null; target: string | null }>
  router: ConnectionRouter
  /** When it was last touched, so a stale draft can be reasoned about later. */
  at: number
}

const key = (workspace: string, view: string) => `archgrid:layout:${workspace}:${view}`

export function readDraft(workspace: string, view: string): LayoutDraft | null {
  try {
    const raw = window.localStorage.getItem(key(workspace, view))
    if (!raw) {
      return null
    }
    const draft = JSON.parse(raw) as LayoutDraft
    // A draft from a future version of the app is not ours to interpret; the server's layout is
    // always a safe fallback, so forget it rather than guess.
    return draft.version === 2 ? draft : null
  } catch {
    return null
  }
}

export function writeDraft(workspace: string, view: string, draft: Omit<LayoutDraft, 'version' | 'at'>) {
  try {
    const payload: LayoutDraft = { ...draft, version: 2, at: Date.now() }
    window.localStorage.setItem(key(workspace, view), JSON.stringify(payload))
  } catch {
    // A full or disabled store must not break editing. The change is still in memory and still
    // goes to the server on save; only surviving a reload is lost.
  }
}

export function clearDraft(workspace: string, view: string) {
  try {
    window.localStorage.removeItem(key(workspace, view))
  } catch {
    // Nothing to do: the draft is already applied and saved.
  }
}
