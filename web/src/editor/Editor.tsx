import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import type { Api } from '../api/client.ts'
import { ApiError } from '../api/client.ts'
import type { Command, ElementType, ModelElement, ModelRelationship, Profile } from '../api/types.ts'
import { Metamodel } from '../metamodel/Metamodel.ts'
import Canvas, { type CanvasElement, type CanvasLink, type Geometry } from './Canvas.tsx'
import ContextMenu, { type ContextTarget } from './ContextMenu.tsx'
import Explorer from './Explorer.tsx'
import FolderTree, { type TreeItem } from './FolderTree.tsx'
import MagicMenu from './MagicMenu.tsx'
import Palette from './Palette.tsx'
import Properties from './Properties.tsx'
import Splitter from './Splitter.tsx'
import RelationshipPicker from './RelationshipPicker.tsx'
import { NODE_HEIGHT, NODE_WIDTH, autoLayout, nextFreePosition } from './layout.ts'
import { invert, useHistoryStore, type Position } from './store.ts'

interface EditorProps {
  api: Api
  workspace: string
  metamodel: Metamodel
  profiles: Profile[]
}

type Size = { width: number; height: number }

/**
 * The editor is view-centric: the canvas draws one view, and the repository is browsed beside
 * it (ADR-0009). An element belongs to the repository; a view merely shows it.
 */
export default function Editor({ api, workspace, metamodel, profiles }: EditorProps) {
  const queryClient = useQueryClient()
  const [viewKey, setViewKey] = useState<string | null>(null)
  const [relationshipType, setRelationshipType] = useState('Serving')
  const [message, setMessage] = useState<{ kind: 'error' | 'info'; text: string } | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  const [context, setContext] = useState<ContextTarget | null>(null)
  const [sidebar, setSidebar] = useState<'views' | 'palette' | 'repository'>('views')
  // The element whose name is being typed in place, straight after creation.
  const [editingKey, setEditingKey] = useState<string | null>(null)
  // Where a line was dropped on empty canvas, and what it came from.
  const [magic, setMagic] = useState<{ x: number; y: number; source: string; at: Position } | null>(null)
  const [picker, setPicker] = useState<{
    x: number
    y: number
    source: string
    target: string
    reason: string
  } | null>(null)
  // Positions as they were when a drag began, so the move can be undone as one action.
  const dragOrigin = useRef<Record<string, Position> | null>(null)
  /**
   * Where an element the user has just asked for should land.
   *
   * Kept in a ref rather than in `positions`, because the command that creates it refetches the
   * view, and the server layout comes back without the new element — it is saved separately and
   * later. Seeding the state was therefore erased a moment after it was written.
   */
  const placeAt = useRef<Record<string, Position>>({})
  // Changing this asks the canvas to refit; it changes when a view is opened or auto-laid out.
  const [fitToken, setFitToken] = useState<string | null>(null)
  // Bumped whenever geometry changes for a reason the canvas cannot know: a view opening,
  // auto-layout, or an undone move. Otherwise the canvas keeps what the user dragged.
  const [geometryVersion, setGeometryVersion] = useState(0)
  const fittedFor = useRef<string | null>(null)
  const history = useHistoryStore()

  const graph = useQuery({ queryKey: ['graph', workspace], queryFn: () => api.graph(workspace) })
  const views = useQuery({ queryKey: ['views', workspace], queryFn: () => api.views(workspace) })
  const repository = useQuery({
    queryKey: ['repository', workspace],
    queryFn: async () => {
      const [elements, relationships] = await Promise.all([api.elements(workspace), api.relationships(workspace)])
      return { elements, relationships }
    },
  })
  const viewFolders = useQuery({
    queryKey: ['folders', workspace, 'views'],
    queryFn: () => api.folders(workspace, 'views'),
  })
  const elementFolders = useQuery({
    queryKey: ['folders', workspace, 'elements'],
    queryFn: () => api.folders(workspace, 'elements'),
  })
  useEffect(() => {
    fittedFor.current = null
  }, [viewKey])

  const view = useQuery({
    queryKey: ['view', workspace, viewKey],
    enabled: viewKey != null,
    queryFn: () => api.view(workspace, viewKey!),
  })

  const headCommitId = graph.data?.headCommitId ?? null
  const allElements = useMemo(() => repository.data?.elements ?? [], [repository.data])
  const allRelationships = useMemo(() => repository.data?.relationships ?? [], [repository.data])
  const byKey = useMemo(
    () => Object.fromEntries(allElements.map((e) => [e.key, e])) as Record<string, ModelElement>,
    [allElements],
  )
  const relationshipsByKey = useMemo(
    () => Object.fromEntries(allRelationships.map((r) => [r.key, r])) as Record<string, ModelRelationship>,
    [allRelationships],
  )

  const onView = useMemo(() => view.data?.elements ?? [], [view.data])
  const onViewKeys = useMemo(() => new Set(onView.map((element) => element.key)), [onView])
  /** Absolute geometry per element: where it is and, once resized, how big. */
  const [positions, setPositions] = useState<Record<string, Position>>({})
  const [sizes, setSizes] = useState<Record<string, Size>>({})
  // Panel widths are pure presentation, so they live in the browser and nowhere else.
  const [leftWidth, setLeftWidth] = useState(240)
  const [rightWidth, setRightWidth] = useState(288)
  const [leftCollapsed, setLeftCollapsed] = useState(false)
  const [rightCollapsed, setRightCollapsed] = useState(false)

  // Server layout is the truth; local positions mirror it so dragging feels immediate.
  useEffect(() => {
    if (!view.data) {
      return
    }
    const stored: Record<string, { x: number; y: number }> = {}
    const storedSizes: Record<string, { width: number; height: number }> = {}
    for (const node of view.data.layout) {
      stored[node.element] = { x: node.x, y: node.y }
      if (node.width && node.height) {
        storedSizes[node.element] = { width: node.width, height: node.height }
      }
    }
    setPositions(stored)
    setSizes(storedSizes)
    setGeometryVersion((version) => version + 1)
  }, [view.data])

  const refresh = useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['graph', workspace] }),
      queryClient.invalidateQueries({ queryKey: ['repository', workspace] }),
      queryClient.invalidateQueries({ queryKey: ['views', workspace] }),
      queryClient.invalidateQueries({ queryKey: ['view', workspace] }),
    ])
  }, [queryClient, workspace])

  const apply = useMutation({
    mutationFn: async ({ commands, label }: { commands: Command[]; label: string }) => {
      const inverse = invert(commands, { elements: byKey, relationships: relationshipsByKey })
      await api.applyCommands(workspace, { baseCommitId: headCommitId, message: label, commands })
      return { inverse, commands, label }
    },
    onSuccess: async ({ inverse, commands, label }) => {
      history.push({ kind: 'commands', forward: commands, inverse, label })
      setMessage(null)
      await refresh()
    },
    onError: (error: unknown) => setMessage({ kind: 'error', text: describe(error) }),
  })

  const applySilently = useCallback(
    async (commands: Command[], label: string) => {
      try {
        await api.applyCommands(workspace, { baseCommitId: headCommitId, message: label, commands })
        setMessage(null)
        await refresh()
        return true
      } catch (error) {
        setMessage({ kind: 'error', text: describe(error) })
        return false
      }
    },
    [api, workspace, headCommitId, refresh],
  )

  /**
   * Coordinates go straight to their own endpoint, never through a command (ADR-0003).
   *
   * Geometry travels as one argument rather than positions here and sizes read from state. A
   * caller that changes both in the same gesture would otherwise race its own re-render and save
   * the size it had a moment ago.
   */
  const saveLayout = useMutation({
    mutationFn: ({ positions: next, sizes: nextSizes }: Geometry) =>
      api.saveLayout(
        workspace,
        viewKey!,
        Object.entries(next).map(([element, position]) => ({
          element,
          x: position.x,
          y: position.y,
          width: nextSizes[element]?.width ?? NODE_WIDTH,
          height: nextSizes[element]?.height ?? NODE_HEIGHT,
        })),
      ),
  })

  const pendingSave = useRef<number | null>(null)
  const scheduleLayoutSave = useCallback(
    (next: Record<string, { x: number; y: number }>, nextSizes?: Record<string, Size>) => {
      if (!viewKey) {
        return
      }
      const geometry = { positions: next, sizes: nextSizes ?? sizes }
      // Debounced: a drag emits a position per frame, and one request per frame would be
      // both wasteful and a guaranteed source of out-of-order writes.
      if (pendingSave.current) {
        window.clearTimeout(pendingSave.current)
      }
      pendingSave.current = window.setTimeout(() => saveLayout.mutate(geometry), 500)
    },
    [viewKey, saveLayout, sizes],
  )

  // Place anything on the view that has no stored coordinates yet.
  useEffect(() => {
    if (!view.data || onView.length === 0) {
      return
    }
    const missing = onView.filter((element) => !positions[element.key])
    if (missing.length === 0) {
      return
    }
    if (Object.keys(positions).length === 0) {
      void autoLayout(onView, view.data.relationships).then((laid) => {
        setPositions(laid)
        scheduleLayoutSave(laid)
      })
    } else {
      let taken = positions
      const placed: Record<string, { x: number; y: number }> = {}
      for (const element of missing) {
        // A place the user pointed at beats the tidy grid every time.
        const asked = placeAt.current[element.key]
        delete placeAt.current[element.key]
        const position = asked ?? nextFreePosition(taken, sizes)
        placed[element.key] = position
        taken = { ...taken, [element.key]: position }
      }
      const next = { ...positions, ...placed }
      setPositions(next)
      scheduleLayoutSave(next)
    }
  }, [view.data, onView, positions, sizes, scheduleLayoutSave])

  // Fitting waits for real coordinates. Auto-layout resolves through ELK after the canvas mounts,
  // and refitting before that opens the view zoomed into an empty corner.
  useEffect(() => {
    if (!viewKey || fittedFor.current === viewKey || onView.length === 0) {
      return
    }
    if (onView.every((element) => positions[element.key])) {
      fittedFor.current = viewKey
      setFitToken(`${viewKey}:${onView.length}`)
    }
  }, [viewKey, onView, positions])

  /** Which elements on this view contain others that are also on it. */
  const containers = useMemo(() => {
    const withChildren = new Set<string>()
    for (const element of onView) {
      if (element.parent && onViewKeys.has(element.parent)) {
        withChildren.add(element.parent)
      }
    }
    return withChildren
  }, [onView, onViewKeys])

  const canvasElements: CanvasElement[] = useMemo(
    () =>
      onView.map((element) => {
        const type = metamodel.elementType(element.conceptType)
        const at = positions[element.key] ?? { x: 0, y: 0 }
        const size = sizes[element.key]
        const container = containers.has(element.key)
        return {
          key: element.key,
          name: element.name,
          typeLabel: element.profile ?? type?.name ?? element.conceptType,
          layer: type?.layer ?? 'other',
          specialised: element.profile != null,
          concept: element.conceptType,
          // Only a container that is itself on the view: nesting is a drawing of containment,
          // and a parent nobody can see is not one.
          parent: element.parent && onViewKeys.has(element.parent) ? element.parent : null,
          x: at.x,
          y: at.y,
          width: size?.width ?? (container ? 320 : NODE_WIDTH),
          height: size?.height ?? (container ? 200 : NODE_HEIGHT),
        }
      }),
    [onView, onViewKeys, positions, sizes, containers, metamodel],
  )

  const canvasLinks: CanvasLink[] = useMemo(() => {
    const explicit = (view.data?.relationships ?? []).map((relationship) => ({
      key: relationship.key,
      source: relationship.source,
      target: relationship.target,
      label: relationship.name ?? relationship.type,
      type: relationship.type,
      derived: false,
    }))

    // Derived lines keep the notation of the relationship they stand for but are drawn faintly
    // and labelled as such. An architect must be able to see at a glance which lines the model
    // actually asserts and which merely follow from it.
    const derived = (view.data?.derived ?? []).map((relationship) => ({
      key: relationship.key,
      source: relationship.source,
      target: relationship.target,
      label: `${relationship.type} (derived)`,
      type: relationship.type,
      derived: true,
    }))

    return [...explicit, ...derived]
  }, [view.data])

  /** Live geometry from a drag or resize. The save is debounced; the state is not. */
  const onGeometry = useCallback(
    (next: Geometry) => {
      setPositions(next.positions)
      setSizes(next.sizes)
      scheduleLayoutSave(next.positions, next.sizes)
    },
    [scheduleLayoutSave],
  )

  /**
   * A drag becomes one undoable action, recorded on release rather than per frame.
   *
   * Moving a box is not a model change and produces no commit (ADR-0003), but it is very much
   * something the user just did, so it belongs on the same stack as everything else.
   */
  const onGeometryStart = useCallback(() => {
    dragOrigin.current = positions
  }, [positions])

  const onGeometryEnd = useCallback(
    (next: Geometry) => {
      const before = dragOrigin.current
      dragOrigin.current = null
      setPositions(next.positions)
      setSizes(next.sizes)
      scheduleLayoutSave(next.positions, next.sizes)
      if (!before || !viewKey) {
        return
      }
      const changed = Object.keys(next.positions).some(
        (key) => before[key]?.x !== next.positions[key]?.x || before[key]?.y !== next.positions[key]?.y,
      )
      if (changed) {
        history.push({ kind: 'layout', view: viewKey, before, after: next.positions, label: 'Move' })
      }
    },
    [viewKey, history, scheduleLayoutSave],
  )

  /**
   * Dropping a node onto another puts it inside — the gesture people already expect.
   *
   * Containment is a model change, so it goes through {@code MoveElement} and produces a commit,
   * unlike the coordinates that arrived with the same drag. The two travel separately on purpose
   * (ADR-0003), and this is the one gesture that does both at once. The canvas has already
   * decided *what* contains what and grown the frame to fit; only the command belongs here.
   */
  const onNest = useCallback(
    async (key: string, parent: string | null, next: Geometry) => {
      const dragged = byKey[key]
      if (!dragged || (dragged.parent ?? null) === parent) {
        return
      }
      setPositions(next.positions)
      setSizes(next.sizes)
      // Awaited before the command, which refetches the view: a debounced save still in flight
      // would be answered with the geometry the server held before the drop.
      await saveLayout.mutateAsync(next)
      apply.mutate({
        commands: [{ op: 'move-element', key, parent: parent ?? undefined }],
        label: parent
          ? `Put ${dragged.name} inside ${byKey[parent]?.name ?? parent}`
          : `Take ${dragged.name} out of its container`,
      })
    },
    [byKey, apply, saveLayout],
  )

  /**
   * Whether a connection may be dropped at all.
   *
   * Deliberately looser than "the type on the toolbar is permitted": if anything at all can
   * connect these two, the drop is allowed and the picker sorts out which. Refusing the drop
   * would throw away the user's gesture in order to make them change a dropdown first.
   */
  const canConnect = useCallback(
    (sourceKey: string, targetKey: string) => {
      const source = byKey[sourceKey]
      const target = byKey[targetKey]
      return Boolean(source && target && metamodel.permittedBetween(source.conceptType, target.conceptType).length > 0)
    },
    [byKey, metamodel],
  )

  const connect = useCallback(
    (sourceKey: string, targetKey: string, type: string) => {
      const source = byKey[sourceKey]
      const target = byKey[targetKey]
      const key = uniqueKey(`${sourceKey}-${type.toLowerCase()}-${targetKey}`, relationshipsByKey)
      apply.mutate({
        commands: [{ op: 'add-relationship', key, type, source: sourceKey, target: targetKey }],
        label: `Connect ${source?.name ?? sourceKey} to ${target?.name ?? targetKey}`,
      })
    },
    [byKey, relationshipsByKey, apply],
  )

  const onConnect = useCallback(
    (sourceKey: string, targetKey: string) => {
      const source = byKey[sourceKey]
      const target = byKey[targetKey]
      if (!source || !target) {
        return
      }
      if (metamodel.permits(source.conceptType, target.conceptType, relationshipType)) {
        connect(source.key, target.key, relationshipType)
        return
      }
      // The metamodel knows what *would* work here, so offer that instead of only refusing.
      // This is the difference between a tool that prevents mistakes and one that teaches the
      // notation.
      const permitted = metamodel.permittedBetween(source.conceptType, target.conceptType)
      if (permitted.length === 1) {
        // Asking someone to choose from a list of one is an action that carries no decision.
        // Draw it, name it, and let undo be the way back.
        connect(source.key, target.key, permitted[0].concept)
        setMessage({
          kind: 'info',
          text: `${permitted[0].name} is the only relationship ArchiMate permits from ${source.name} to ${target.name}, so that is what was drawn.`,
        })
        return
      }
      const node = document.querySelector(`.ag-node[data-key="${CSS.escape(target.key)}"]`)
      const box = node?.getBoundingClientRect()
      setPicker({
        x: box ? box.right + 8 : window.innerWidth / 2,
        y: box ? box.top : window.innerHeight / 2,
        source: source.key,
        target: target.key,
        reason: metamodel.explain(source.conceptType, target.conceptType, relationshipType),
      })
    },
    [byKey, metamodel, relationshipType, connect],
  )

  /** New elements join the repository and land on the current view in one commit. */
  const createElement = useCallback(
    (typeRef: string, displayName: string) => {
      const key = uniqueKey(displayName, byKey)
      const commands: Command[] = [{ op: 'create-element', key, type: typeRef, name: displayName }]
      if (viewKey) {
        commands.push({ op: 'add-to-view', key: viewKey, element: key })
      }
      apply.mutate({ commands, label: `Add ${displayName}` })
      // It is named after its type until the user says otherwise, so put the cursor there now
      // rather than sending them to a side panel for every element they create.
      setEditingKey(key)
    },
    [byKey, viewKey, apply],
  )

  /**
   * The Magic Connector's other half: an element and the relationship reaching it, in one commit.
   *
   * One commit rather than two on purpose. "Added Ledger" followed by "Connected Payment API to
   * Ledger" is two entries in the history for one decision, and undo would then take two presses
   * to get back to where the user started.
   */
  const createConnected = useCallback(
    (sourceKey: string, relationship: string, type: ElementType, at: Position) => {
      const source = byKey[sourceKey]
      if (!source || !viewKey) {
        return
      }
      const key = uniqueKey(type.name, byKey)
      // Centred on where the line was dropped, not hung off it by a corner: the cursor was
      // pointing at where the element should be, not at its top-left pixel.
      placeAt.current[key] = { x: Math.round(at.x - NODE_WIDTH / 2), y: Math.round(at.y - NODE_HEIGHT / 2) }
      const relationshipKey = uniqueKey(`${sourceKey}-${relationship.toLowerCase()}-${key}`, relationshipsByKey)
      apply.mutate({
        commands: [
          { op: 'create-element', key, type: type.concept, name: type.name },
          { op: 'add-to-view', key: viewKey, element: key },
          { op: 'add-relationship', key: relationshipKey, type: relationship, source: sourceKey, target: key },
        ],
        label: `${relationship} ${type.name} from ${source.name}`,
      })
      // Named after its type until the user says otherwise, so put the cursor there straight away.
      setEditingKey(key)
    },
    [byKey, relationshipsByKey, viewKey, apply],
  )

  const addToView = useCallback(
    (elementKey: string) => {
      if (!viewKey) {
        setMessage({ kind: 'info', text: 'Open a view first, then add elements to it.' })
        return
      }
      apply.mutate({
        commands: [{ op: 'add-to-view', key: viewKey, element: elementKey }],
        label: `Show ${byKey[elementKey]?.name ?? elementKey}`,
      })
    },
    [viewKey, byKey, apply],
  )

  /** Off this diagram only. The element stays in the repository and on every other view. */
  const removeFromView = useCallback(
    (keys: string[]) => {
      if (!viewKey || keys.length === 0) {
        return
      }
      setContext(null)
      apply.mutate({
        commands: keys.map((key) => ({ op: 'remove-from-view', key: viewKey, element: key })),
        label: `Remove ${keys.map((k) => byKey[k]?.name ?? k).join(', ')} from ${view.data?.name ?? viewKey}`,
      })
    },
    [viewKey, byKey, view.data, apply],
  )

  /** Out of the repository entirely: permanent, off every view, and it takes its relationships. */
  const deleteFromModel = useCallback(
    (keys: string[]) => {
      if (keys.length === 0) {
        return
      }
      const affected = allRelationships.filter((r) => keys.includes(r.source) || keys.includes(r.target))
      const shownOn = (views.data ?? []).length
      const names = keys.map((key) => byKey[key]?.name ?? key).join(', ')
      setContext(null)
      const confirmed = window.confirm(
        `Delete ${names} from the repository?\n\n` +
          (affected.length > 0 ? `${affected.length} relationship(s) will be deleted with it.\n` : '') +
          `It will disappear from every view (${shownOn} exist), not just this one.\n\n` +
          'To take it off this diagram only, use "Remove from view".',
      )
      if (confirmed) {
        apply.mutate({ commands: keys.map((key) => ({ op: 'delete-element', key })), label: `Delete ${names}` })
      }
    },
    [allRelationships, views.data, byKey, apply],
  )

  const createView = useCallback(async () => {
    const name = window.prompt('Name of the new view', 'Context')
    if (!name?.trim()) {
      return
    }
    const key = uniqueKey(name, Object.fromEntries((views.data ?? []).map((v) => [v.key, true])))
    // Open it only once the command has landed. Selecting it first made the detail query fire
    // against a view the server did not have yet, which answered 404 and left the canvas blank.
    if (
      await applySilently(
        [{ op: 'create-view', key, name: name.trim(), viewpoint: 'layered' }],
        `Create view ${name.trim()}`,
      )
    ) {
      setViewKey(key)
    }
  }, [views.data, applySilently])

  /**
   * Restores a set of coordinates and persists them. Undoing a move is itself not a commit.
   */
  const restoreLayout = useCallback(
    (positionsToRestore: Record<string, Position>) => {
      setPositions(positionsToRestore)
      scheduleLayoutSave(positionsToRestore)
      return true
    },
    [scheduleLayoutSave],
  )

  /**
   * Undoes the last thing the user did, of either kind.
   *
   * The two kinds travel by different routes — commands to the server, coordinates to the
   * layout endpoint — but they share one stack, because "undo what I just did" is not a
   * question about which architectural layer the action belonged to.
   */
  const undo = useCallback(async () => {
    const entry = history.popUndo()
    if (!entry) {
      return
    }
    const ok =
      entry.kind === 'commands'
        ? await applySilently(entry.inverse, `Undo: ${entry.label}`)
        : restoreLayout(entry.before)
    if (ok) {
      history.pushRedo(entry)
    }
  }, [history, applySilently, restoreLayout])

  const redo = useCallback(async () => {
    const entry = history.popRedo()
    if (!entry) {
      return
    }
    const ok =
      entry.kind === 'commands'
        ? await applySilently(entry.forward, `Redo: ${entry.label}`)
        : restoreLayout(entry.after)
    if (ok) {
      history.push(entry)
    }
  }, [history, applySilently, restoreLayout])

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      if (target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) {
        return
      }
      const mod = event.ctrlKey || event.metaKey
      const key = event.key.toLowerCase()
      if (mod && key === 'z' && !event.shiftKey) {
        event.preventDefault()
        void undo()
      } else if (mod && ((key === 'z' && event.shiftKey) || key === 'y')) {
        event.preventDefault()
        void redo()
      } else if ((event.key === 'Delete' || event.key === 'Backspace') && selected.length > 0) {
        // The easiest key to hit by accident takes things off the diagram, never out of the
        // repository.
        event.preventDefault()
        removeFromView(selected)
      } else if (event.key === 'Escape') {
        setContext(null)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [undo, redo, selected, removeFromView])

  const viewItems: TreeItem[] = useMemo(
    () =>
      (views.data ?? []).map((summary) => ({
        key: summary.key,
        label: summary.name,
        meta: `${summary.elements}`,
        folder: summary.folder,
      })),
    [views.data],
  )

  const folderMutation = useMutation({
    mutationFn: async (action: () => Promise<unknown>) => action(),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['folders', workspace] }),
        queryClient.invalidateQueries({ queryKey: ['views', workspace] }),
        queryClient.invalidateQueries({ queryKey: ['repository', workspace] }),
      ]),
    onError: (error: unknown) => setMessage({ kind: 'error', text: describe(error) }),
  })

  function folderActions(kind: 'views' | 'elements') {
    return {
      onCreateFolder: (parent: string | null) => {
        const name = window.prompt('Folder name')
        if (name?.trim()) {
          folderMutation.mutate(() => api.createFolder(workspace, kind, name.trim(), parent))
        }
      },
      onDeleteFolder: (id: string) => folderMutation.mutate(() => api.deleteFolder(workspace, id)),
      onMoveItem: (key: string, folder: string | null) =>
        folderMutation.mutate(() => api.assignFolder(workspace, kind, key, folder)),
    }
  }

  if (graph.isPending || repository.isPending) {
    return <div className="ag-editor-loading">Loading repository…</div>
  }

  return (
    <div
      className="ag-editor"
      style={{
        // One track per rendered child, not five with two of them zero: a collapsed panel is
        // unmounted, and a leftover track would shift the canvas and both splitters along.
        gridTemplateColumns: [
          leftCollapsed ? null : `${leftWidth}px`,
          '6px',
          'minmax(0, 1fr)',
          '6px',
          rightCollapsed ? null : `${rightWidth}px`,
        ]
          .filter(Boolean)
          .join(' '),
      }}
    >
      {/*
        Unmounted rather than hidden. `.ag-sidebar` sets `display: flex`, which beats the `hidden`
        attribute's user-agent `display: none`, so the panel stayed on screen and spilled over a
        zero-width grid column.
      */}
      {!leftCollapsed && (
      <div className="ag-sidebar">
        <div className="ag-tabs" role="tablist">
          {(['views', 'palette', 'repository'] as const).map((tab) => (
            <button
              key={tab}
              type="button"
              role="tab"
              aria-selected={sidebar === tab}
              className={sidebar === tab ? 'ag-tab ag-tab-active' : 'ag-tab'}
              onClick={() => setSidebar(tab)}
            >
              {tab === 'views' && `Views (${views.data?.length ?? 0})`}
              {tab === 'palette' && 'Palette'}
              {tab === 'repository' && `Repository (${allElements.length})`}
            </button>
          ))}
        </div>

        {sidebar === 'views' && (
          <div className="ag-sidebar-body">
            <button type="button" className="ag-block-button" onClick={() => void createView()} disabled={apply.isPending}>
              + New view
            </button>
            <FolderTree
              folders={viewFolders.data ?? []}
              items={viewItems}
              selected={viewKey ? [viewKey] : []}
              emptyLabel="No views yet. A view is a diagram over the repository."
              onSelect={setViewKey}
              {...folderActions('views')}
            />
          </div>
        )}

        {sidebar === 'palette' && (
          <Palette
            metamodel={metamodel}
            viewpoint={view.data?.viewpoint ?? 'layered'}
            onViewpointChange={(code) =>
              viewKey && apply.mutate({ commands: [{ op: 'set-viewpoint', key: viewKey, viewpoint: code }], label: 'Change viewpoint' })
            }
            onCreate={(type: ElementType) => createElement(type.concept, type.name)}
            profiles={profiles}
            onCreateProfiled={(name) => createElement(name, name)}
            busy={apply.isPending}
          />
        )}

        {sidebar === 'repository' && (
          <Explorer
            elements={allElements}
            relationships={allRelationships}
            folders={elementFolders.data ?? []}
            onView={onViewKeys}
            metamodel={metamodel}
            selected={selected}
            onSelect={(key) => setSelected([key])}
            onAddToView={addToView}
            {...folderActions('elements')}
          />
        )}
      </div>
      )}

      <Splitter
        side="left"
        width={leftWidth}
        min={180}
        max={520}
        collapsed={leftCollapsed}
        label="the sidebar"
        onResize={setLeftWidth}
        onToggle={() => setLeftCollapsed((collapsed) => !collapsed)}
      />

      <div className="ag-canvas-area">
        <div className="ag-toolbar">
          <strong className="ag-view-name">{view.data?.name ?? 'No view open'}</strong>
          <label className="ag-field ag-inline">
            <span>Draw</span>
            <select value={relationshipType} onChange={(event) => setRelationshipType(event.target.value)}>
              {metamodel.relationshipTypes.map((type) => (
                <option key={type.concept} value={type.concept}>
                  {type.name}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            onClick={() =>
              view.data &&
              void autoLayout(onView, view.data.relationships).then((laid) => {
                setPositions(laid)
                scheduleLayoutSave(laid)
                setGeometryVersion((version) => version + 1)
                setFitToken(`auto:${Date.now()}`)
              })
            }
            disabled={!viewKey}
          >
            Auto-layout
          </button>
          <button type="button" onClick={() => void undo()} disabled={history.undoStack.length === 0} title="Ctrl+Z">
            Undo
          </button>
          <button type="button" onClick={() => void redo()} disabled={history.redoStack.length === 0} title="Ctrl+Shift+Z">
            Redo
          </button>
          <button
            type="button"
            onClick={() => removeFromView(selected)}
            disabled={selected.length === 0 || !viewKey}
            title="Delete — takes it off this view only"
          >
            Remove from view
          </button>
          <button
            type="button"
            className="ag-danger"
            onClick={() => deleteFromModel(selected)}
            disabled={selected.length === 0}
          >
            Delete from repository
          </button>
          <span className="ag-toolbar-spacer" />
          <span className="ag-muted">
            {onView.length} on view · {allElements.length} in repository
          </span>
        </div>

        {message && <div className={`ag-banner ag-banner-${message.kind}`}>{message.text}</div>}

        <div className="ag-canvas">
          {viewKey ? (
            <Canvas
              elements={canvasElements}
              links={canvasLinks}
              selected={selected}
              editingKey={editingKey}
              onSelect={setSelected}
              onGeometry={onGeometry}
              onGeometryStart={onGeometryStart}
              onGeometryEnd={onGeometryEnd}
              onNest={(key, parent, next) => void onNest(key, parent, next)}
              onConnect={onConnect}
              onMagic={(source, x, y, at) => setMagic({ source, x, y, at })}
              canConnect={canConnect}
              onContextMenu={(elementKey, x, y) => setContext({ elementKey, x, y })}
              onBlankClick={() => setContext(null)}
              onCommitName={(key, name) => {
                setEditingKey(null)
                if (name) {
                  apply.mutate({ commands: [{ op: 'rename-element', key, name }], label: `Rename to ${name}` })
                }
              }}
              onCancelEdit={() => setEditingKey(null)}
              fitToken={fitToken}
              geometryVersion={geometryVersion}
            />
          ) : (
            <div className="ag-empty-canvas">
              <p>Open a view, or create one.</p>
              <p className="muted">
                A view is a diagram over the repository — a context diagram of one system, the modules of
                another, the course of a process. The same element can appear on any number of them.
              </p>
              <button type="button" onClick={() => void createView()}>
                + New view
              </button>
            </div>
          )}
        </div>
      </div>

      <Splitter
        side="right"
        width={rightWidth}
        min={220}
        max={560}
        collapsed={rightCollapsed}
        label="properties"
        onResize={setRightWidth}
        onToggle={() => setRightCollapsed((collapsed) => !collapsed)}
      />

      {!rightCollapsed && (
      <Properties
        element={selected.length === 1 ? (byKey[selected[0]] ?? null) : null}
        selectedCount={selected.length}
        metamodel={metamodel}
        profile={
          selected.length === 1 ? profiles.find((p) => p.name === byKey[selected[0]]?.profile) : undefined
        }
        relationships={allRelationships}
        elementsByKey={byKey}
        busy={apply.isPending}
        onRename={(key, name) =>
          apply.mutate({ commands: [{ op: 'rename-element', key, name }], label: `Rename to ${name}` })
        }
        onDocumentation={(key, documentation) =>
          apply.mutate({
            commands: [{ op: 'set-element-documentation', key, documentation: documentation || null }],
            label: `Document ${byKey[key]?.name ?? key}`,
          })
        }
        onProperty={(key, property, value) =>
          apply.mutate({
            commands: [{ op: 'set-element-property', key, property, value }],
            label: value === null ? `Remove ${property}` : `Set ${property}`,
          })
        }
        onSelect={(key) => setSelected([key])}
      />
      )}

      {magic && byKey[magic.source] && (
        <MagicMenu
          x={magic.x}
          y={magic.y}
          sourceName={byKey[magic.source].name}
          sourceConcept={byKey[magic.source].conceptType}
          metamodel={metamodel}
          palette={metamodel.palette(view.data?.viewpoint ?? 'layered')}
          onPick={(relationship, type) => {
            const { source, at } = magic
            setMagic(null)
            createConnected(source, relationship, type, at)
          }}
          onCancel={() => setMagic(null)}
        />
      )}

      {picker && (
        <RelationshipPicker
          x={picker.x}
          y={picker.y}
          sourceName={byKey[picker.source]?.name ?? picker.source}
          targetName={byKey[picker.target]?.name ?? picker.target}
          reason={picker.reason}
          permitted={metamodel.permittedBetween(
            byKey[picker.source]?.conceptType ?? '',
            byKey[picker.target]?.conceptType ?? '',
          )}
          onPick={(type) => {
            const { source, target } = picker
            setPicker(null)
            connect(source, target, type)
          }}
          onCancel={() => setPicker(null)}
        />
      )}

      {context && byKey[context.elementKey] && (
        <ContextMenu
          target={context}
          element={byKey[context.elementKey]}
          relationships={allRelationships}
          elementsByKey={byKey}
          metamodel={metamodel}
          onClose={() => setContext(null)}
          onRemoveFromView={() => removeFromView([context.elementKey])}
          onDeleteFromModel={() => deleteFromModel([context.elementKey])}
        />
      )}
    </div>
  )
}

function slugify(value: string): string {
  return (
    value
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 56) || 'element'
  )
}

function uniqueKey(base: string, taken: Record<string, unknown>): string {
  const root = slugify(base)
  if (!(root in taken)) {
    return root
  }
  for (let suffix = 2; ; suffix++) {
    const candidate = `${root}-${suffix}`
    if (!(candidate in taken)) {
      return candidate
    }
  }
}

function describe(error: unknown): string {
  if (error instanceof ApiError) {
    const violations = error.problem.violations
    if (violations && violations.length > 0) {
      return violations.map((violation) => violation.detail).join(' ')
    }
    if (error.isStaleBase) {
      return 'Someone else changed this repository. Reload to get their changes before continuing.'
    }
    return error.message
  }
  return error instanceof Error ? error.message : String(error)
}
