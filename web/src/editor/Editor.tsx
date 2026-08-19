import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import type { Api } from '../api/client.ts'
import { ApiError } from '../api/client.ts'
import type {
  Command,
  ConnectionRouter,
  ElementType,
  Graph,
  ModelElement,
  ModelRelationship,
  Profile,
  RelationshipType,
} from '../api/types.ts'
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
import { GRID, NODE_HEIGHT, NODE_WIDTH, autoLayout, nextFreePosition } from './layout.ts'
import { clearDraft, readDraft, writeDraft } from './draft.ts'
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
  const [message, setMessage] = useState<{ kind: 'error' | 'info'; text: string } | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  // A relationship can be selected too, and until it could there was no way to delete one.
  const [selectedLink, setSelectedLink] = useState<string | null>(null)
  // What the editor is asking about a nesting the user has just made.
  const [nesting, setNesting] = useState<{
    x: number
    y: number
    container: string
    child: string
    permitted: RelationshipType[]
  } | null>(null)
  // The connector tool: active, and the element clicked as the source of the next line.
  const [connecting, setConnecting] = useState(false)
  const [connectFrom, setConnectFrom] = useState<string | null>(null)
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
  /**
   * What each element is drawn inside, on this view only.
   *
   * Nesting is presentation (ADR-0011): the same element may sit inside a container on the
   * context diagram and stand alone on another, which is the whole reason views exist.
   */
  const [parents, setParents] = useState<Record<string, string | null>>({})
  /**
   * The same value, readable synchronously.
   *
   * The debounced layout save runs after the render that scheduled it, and read the nesting as it
   * was before the drop — so a save queued by the drag landed after the nesting was written and
   * quietly put it back to null. State lags by a render; this does not.
   */
  const parentsRef = useRef<Record<string, string | null>>({})
  /**
   * Which view the geometry in `positions` belongs to.
   *
   * State, not a ref, and that is the whole point. Both the effect that loads a view's layout and
   * the effect that places anything unplaced run in the same commit; a ref would already hold the
   * new key while `positions` still held the previous view's coordinates, which is exactly the
   * trap being guarded against. State changes with them, so placement waits for the commit where
   * the coordinates are this view's own.
   *
   * Without the guard, opening a second view found every one of its elements "missing", laid them
   * out on the grid and saved that over the real layout — so a view reopened later came back
   * scrambled, and the damage was permanent.
   */
  const [geometryFor, setGeometryFor] = useState<string | null>(null)
  /** How this view draws its lines, as the user has it — saved with everything else. */
  const [router, setRouter] = useState<ConnectionRouter>('straight')
  // Readable synchronously, for the same reason the nesting is: a debounced write runs after the
  // render that scheduled it and would otherwise record the routing as it was before the change.
  const routerRef = useRef<ConnectionRouter>('straight')
  /** Whether the arrangement in hand differs from the one the server holds. */
  const [dirty, setDirty] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  /**
   * The type being carried from the palette, and where the cursor is holding it.
   *
   * Carried rather than clicked: a click has to invent a position, and the one it invents is
   * never the one the user had in mind. Pointer events rather than HTML5 drag-and-drop, because
   * the canvas needs the drop point in its own coordinates and a native drag image would have to
   * be fought rather than used.
   */
  const [dropping, setDropping] = useState<{ type: ElementType; profile?: string } | null>(null)
  const [dropAt, setDropAt] = useState<{ x: number; y: number } | null>(null)
  const rememberParents = useCallback((next: Record<string, string | null>) => {
    parentsRef.current = next
    setParents(next)
  }, [])
  // Panel widths are pure presentation, so they live in the browser and nowhere else.
  const [leftWidth, setLeftWidth] = useState(240)
  const [rightWidth, setRightWidth] = useState(288)
  const [leftCollapsed, setLeftCollapsed] = useState(false)
  const [rightCollapsed, setRightCollapsed] = useState(false)

  /**
   * What the view looks like: the server's arrangement with the local draft laid over it.
   *
   * Per element rather than wholesale, so an element created since the draft was made still gets
   * the position the server or the placement rule gave it, while everything the user has moved
   * keeps where they put it.
   */
  useEffect(() => {
    if (!view.data) {
      return
    }
    const stored: Record<string, { x: number; y: number }> = {}
    const storedSizes: Record<string, { width: number; height: number }> = {}
    const storedParents: Record<string, string | null> = {}
    for (const node of view.data.layout) {
      stored[node.element] = { x: node.x, y: node.y }
      storedParents[node.element] = node.parent
      if (node.width && node.height) {
        storedSizes[node.element] = { width: node.width, height: node.height }
      }
    }
    const draft = readDraft(workspace, view.data.key)
    setPositions(draft ? { ...stored, ...draft.positions } : stored)
    setSizes(draft ? { ...storedSizes, ...draft.sizes } : storedSizes)
    rememberParents(draft ? { ...storedParents, ...draft.parents } : storedParents)
    setRouter(draft?.router ?? view.data.router)
    routerRef.current = draft?.router ?? view.data.router
    setDirty(draft != null)
    setGeometryFor(view.data.key)
    setGeometryVersion((version) => version + 1)
  }, [view.data, workspace, rememberParents])

  const refresh = useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['graph', workspace] }),
      queryClient.invalidateQueries({ queryKey: ['repository', workspace] }),
      queryClient.invalidateQueries({ queryKey: ['views', workspace] }),
      queryClient.invalidateQueries({ queryKey: ['view', workspace] }),
    ])
  }, [queryClient, workspace])

  /**
   * The commit the next command builds on.
   *
   * Read from the cache at the moment of sending, never from the render that scheduled it. The
   * optimistic check refuses a command whose base is not the current head (ADR-0004), and a value
   * captured in a closure goes stale the instant the previous command lands — which is a normal
   * thing to do, since the editor commits on almost every gesture. Working alone in an empty
   * repository, the user was told someone else had changed it.
   */
  const currentHead = useCallback(
    () => queryClient.getQueryData<Graph>(['graph', workspace])?.headCommitId ?? headCommitId,
    [queryClient, workspace, headCommitId],
  )

  /** The server tells us the new head; believing it beats waiting for the graph to be refetched. */
  const advanceHead = useCallback(
    (commitId: string) =>
      queryClient.setQueryData<Graph>(['graph', workspace], (previous) =>
        previous ? { ...previous, headCommitId: commitId } : previous,
      ),
    [queryClient, workspace],
  )

  const apply = useMutation({
    mutationFn: async ({ commands, label }: { commands: Command[]; label: string }) => {
      const inverse = invert(commands, { elements: byKey, relationships: relationshipsByKey })
      const result = await api.applyCommands(workspace, {
        baseCommitId: currentHead(),
        message: label,
        commands,
      })
      return { inverse, commands, label, result }
    },
    onSuccess: async ({ inverse, commands, label, result }) => {
      advanceHead(result.commitId)
      history.push({ kind: 'commands', forward: commands, inverse, label })
      setMessage(null)
      await refresh()
    },
    onError: (error: unknown) => {
      setMessage({ kind: 'error', text: describe(error) })
      // A rejected base means what we hold is out of date, so fetch what is current rather than
      // leaving the user to press things until it works. The command is not retried: the check
      // exists precisely so a change is never replayed onto a model that moved underneath it.
      if (error instanceof ApiError && error.isStaleBase) {
        void refresh()
      }
    },
  })

  const applySilently = useCallback(
    async (commands: Command[], label: string) => {
      try {
        const result = await api.applyCommands(workspace, {
          baseCommitId: currentHead(),
          message: label,
          commands,
        })
        advanceHead(result.commitId)
        setMessage(null)
        await refresh()
        return true
      } catch (error) {
        setMessage({ kind: 'error', text: describe(error) })
        return false
      }
    },
    [api, workspace, currentHead, advanceHead, refresh],
  )

  /** Coordinates go to their own endpoint, never through a command (ADR-0003). */
  const saveLayout = useMutation({
    mutationFn: (geometry: Geometry & { router: ConnectionRouter }) =>
      api.saveLayout(workspace, viewKey!, layoutNodes(geometry), geometry.router),
  })

  const pendingSave = useRef<number | null>(null)

  /**
   * Records a change locally. Nothing reaches the server until the user saves.
   *
   * Debounced against the browser's own store only: a drag emits a position per frame, and
   * serialising the whole arrangement on each one would be wasteful for no gain.
   */
  const rememberChange = useCallback(
    (
      next: Record<string, { x: number; y: number }>,
      nextSizes?: Record<string, Size>,
      nextParents?: Record<string, string | null>,
      nextRouter?: ConnectionRouter,
    ) => {
      if (!viewKey) {
        return
      }
      const geometry = {
        positions: next,
        sizes: nextSizes ?? sizes,
        parents: nextParents ?? parentsRef.current,
        router: nextRouter ?? routerRef.current,
      }
      setDirty(true)
      if (pendingSave.current) {
        window.clearTimeout(pendingSave.current)
      }
      pendingSave.current = window.setTimeout(() => writeDraft(workspace, viewKey, geometry), 250)
    },
    [workspace, viewKey, sizes],
  )

  /**
   * Ctrl+S, or the button. The arrangement in hand becomes the one the server holds.
   *
   * Only layout: model changes were committed as they were made, because a command is validated
   * and attributed at the moment it is issued (ADR-0004).
   */
  const save = useCallback(async () => {
    if (!viewKey || !dirty) {
      return
    }
    if (pendingSave.current) {
      window.clearTimeout(pendingSave.current)
      pendingSave.current = null
    }
    await saveLayout.mutateAsync({ positions, sizes, parents: parentsRef.current, router })
    clearDraft(workspace, viewKey)
    setDirty(false)
    setMessage({ kind: 'info', text: 'View saved.' })
  }, [viewKey, dirty, saveLayout, positions, sizes, router, workspace])

  /** Throws the local draft away and goes back to what the server holds. */
  const discard = useCallback(async () => {
    if (!viewKey || !window.confirm('Discard the unsaved changes to this view?')) {
      return
    }
    clearDraft(workspace, viewKey)
    setDirty(false)
    await queryClient.invalidateQueries({ queryKey: ['view', workspace] })
  }, [viewKey, workspace, queryClient])

  /**
   * A notice that has served its purpose goes away by itself; a refusal does not.
   *
   * "View saved" is worth a glance and nothing more, while an error is the only account of why
   * something did not happen — clearing it on a timer would hide the one message worth keeping.
   */
  useEffect(() => {
    if (message?.kind !== 'info') {
      return
    }
    const timer = window.setTimeout(() => setMessage(null), 4000)
    return () => window.clearTimeout(timer)
  }, [message])

  // Place anything on the view that has no stored coordinates yet.
  useEffect(() => {
    // Only once the geometry in hand is this view's own. Anything else is the previous view's.
    if (!view.data || onView.length === 0 || geometryFor !== view.data.key) {
      return
    }
    const missing = onView.filter((element) => !positions[element.key])
    if (missing.length === 0) {
      return
    }
    if (Object.keys(positions).length === 0) {
      void autoLayout(onView, view.data.relationships).then((laid) => {
        setPositions(laid)
        rememberChange(laid)
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
      rememberChange(next)
    }
  }, [view.data, onView, positions, sizes, geometryFor, rememberChange])

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
      const parent = parents[element.key]
      if (parent && onViewKeys.has(parent)) {
        withChildren.add(parent)
      }
    }
    return withChildren
  }, [onView, onViewKeys, parents])

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
          // From this view's layout, and only when the container is on the view too: a parent
          // nobody can see is not one.
          parent: parents[element.key] && onViewKeys.has(parents[element.key]!) ? parents[element.key] : null,
          x: at.x,
          y: at.y,
          width: size?.width ?? (container ? 320 : NODE_WIDTH),
          height: size?.height ?? (container ? 200 : NODE_HEIGHT),
        }
      }),
    [onView, onViewKeys, positions, sizes, parents, containers, metamodel],
  )

  /**
   * A relationship the nesting on this view already expresses, so the line would say it twice.
   *
   * Only the structural ones, and only between a container and its own child: those are what the
   * specification allows to be drawn as containment instead of a line. A Serving between the same
   * two elements is a different statement and stays visible.
   *
   * It is hidden, never deleted — the model still asserts it, and it is drawn on any other view
   * where the two are not nested (ADR-0011).
   */
  const expressedByNesting = useCallback(
    (relationship: ModelRelationship) => {
      if (metamodel.relationshipType(relationship.type)?.category !== 'structural') {
        return false
      }
      return (
        parents[relationship.target] === relationship.source ||
        parents[relationship.source] === relationship.target
      )
    },
    [parents, metamodel],
  )

  const canvasLinks: CanvasLink[] = useMemo(() => {
    const explicit = (view.data?.relationships ?? []).filter((r) => !expressedByNesting(r)).map((relationship) => ({
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
  }, [view.data, expressedByNesting])

  /** Live geometry from a drag or resize. The save is debounced; the state is not. */
  const onGeometry = useCallback(
    (next: Geometry) => {
      setPositions(next.positions)
      setSizes(next.sizes)
      rememberChange(next.positions, next.sizes)
    },
    [rememberChange],
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
      rememberChange(next.positions, next.sizes)
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
    [viewKey, history, rememberChange],
  )

  /**
   * Dropping an element into another draws it inside, on this view alone.
   *
   * No command and no commit: which box is drawn inside which is presentation (ADR-0011), so it
   * travels with the coordinates. What the nesting *means* is a separate question, and the answer
   * is a relationship — so if the model does not already say something structural about these two,
   * the editor asks whether it should, exactly as Archi's ARM does.
   */
  const onNest = useCallback(
    (key: string, parent: string | null, next: Geometry) => {
      setPositions(next.positions)
      setSizes(next.sizes)
      rememberParents(next.parents)
      // Recorded like any other change. The refetch that the offered relationship triggers no
      // longer threatens it: the draft is laid over whatever the server returns, so the nesting
      // survives without having to be raced to the server first.
      rememberChange(next.positions, next.sizes, next.parents)
      if (!parent) {
        return
      }
      const container = byKey[parent]
      const child = byKey[key]
      if (!container || !child) {
        return
      }
      // Already said? Then nesting is just a nicer way of drawing what is there.
      const already = allRelationships.some(
        (relationship) =>
          metamodel.relationshipType(relationship.type)?.category === 'structural' &&
          ((relationship.source === parent && relationship.target === key) ||
            (relationship.source === key && relationship.target === parent)),
      )
      if (already) {
        return
      }
      const permitted = metamodel
        .permittedBetween(container.conceptType, child.conceptType)
        .filter((type) => type.category === 'structural')
      if (permitted.length === 0) {
        return
      }
      const node = document.querySelector(`.ag-node[data-key="${CSS.escape(key)}"]`)
      const box = node?.getBoundingClientRect()
      setNesting({
        x: box ? box.right + 8 : window.innerWidth / 2,
        y: box ? box.top : window.innerHeight / 2,
        container: parent,
        child: key,
        permitted,
      })
    },
    [byKey, allRelationships, metamodel, rememberChange, rememberParents],
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

  /**
   * Two ends named; now what goes between them.
   *
   * There is no type chosen in advance any more. Picking one before the ends are known was a
   * guess the editor overrode half the time — the metamodel can only answer once it knows both
   * concepts, and by then the user has said everything needed. This is the Magic Connector and
   * the only way to draw a line.
   */
  const onConnect = useCallback(
    (sourceKey: string, targetKey: string) => {
      const source = byKey[sourceKey]
      const target = byKey[targetKey]
      if (!source || !target) {
        return
      }
      const permitted = metamodel.permittedBetween(source.conceptType, target.conceptType)
      if (permitted.length === 0) {
        setMessage({
          kind: 'error',
          text: `ArchiMate permits no relationship from ${source.name} to ${target.name}.`,
        })
        return
      }
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
        reason: `What does ${source.name} do to ${target.name}? Only what ArchiMate permits is listed.`,
      })
    },
    [byKey, metamodel, connect],
  )

  /**
   * How this view routes its connections.
   *
   * Part of the arrangement, so it goes into the draft with everything else and reaches the
   * server on save. It is a property of the diagram rather than of this browser — a colleague
   * opening the view should see it as it was drawn — which is why it is saved at all.
   */
  const chooseRouter = useCallback(
    (next: ConnectionRouter) => {
      routerRef.current = next
      setRouter(next)
      rememberChange(positions, sizes, parentsRef.current, next)
    },
    [positions, sizes, rememberChange],
  )

  /**
   * One click of the connector tool.
   *
   * The first names the source, the second the target — Archi's own sequence, and the reason
   * there are no dots on the edges of a box. The tool stays on the toolbar rather than on every
   * element, so nothing about drawing lines is in the way while reading a diagram.
   */
  const onConnectPick = useCallback(
    (key: string) => {
      if (!connectFrom) {
        setConnectFrom(key)
        setMessage({ kind: 'info', text: `Connecting from ${byKey[key]?.name ?? key}. Click the other end, or Escape.` })
        return
      }
      if (connectFrom === key) {
        setConnectFrom(null)
        setMessage(null)
        return
      }
      const source = connectFrom
      setConnectFrom(null)
      setConnecting(false)
      setMessage(null)
      onConnect(source, key)
    },
    [connectFrom, byKey, onConnect],
  )

  useEffect(() => {
    if (!dropping) {
      return
    }
    const onMove = (event: PointerEvent) => setDropAt({ x: event.clientX, y: event.clientY })
    // The canvas has its own pointerup on the paper and runs first; this only ends the gesture.
    const onUp = () => {
      setDropping(null)
      setDropAt(null)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
  }, [dropping])

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
      // Snapped to the same grid a drag steps by: a new element half a unit off the one it came
      // from turns the line between them from vertical into very nearly vertical.
      const snap = (value: number) => Math.round(value / GRID) * GRID
      placeAt.current[key] = { x: snap(at.x - NODE_WIDTH / 2), y: snap(at.y - NODE_HEIGHT / 2) }
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

  /**
   * Deletes a relationship from the model.
   *
   * There is no "remove from view" for a line: a view shows the relationships whose ends are both
   * on it (ADR-0009), so the only way to take one off a diagram is to take one of its ends off,
   * or to say the relationship does not exist. Both are honest; hiding a line the model asserts
   * would not be.
   */
  const deleteRelationship = useCallback(
    (key: string) => {
      const relationship = relationshipsByKey[key]
      if (!relationship) {
        return
      }
      const source = byKey[relationship.source]?.name ?? relationship.source
      const target = byKey[relationship.target]?.name ?? relationship.target
      if (!window.confirm(`Delete the ${relationship.type} from ${source} to ${target}?`)) {
        return
      }
      setSelectedLink(null)
      apply.mutate({
        commands: [{ op: 'delete-relationship', key }],
        label: `Delete ${relationship.type} from ${source} to ${target}`,
      })
    },
    [relationshipsByKey, byKey, apply],
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
      rememberChange(positionsToRestore)
      return true
    },
    [rememberChange],
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
      if (mod && key === 's') {
        // The browser's own save dialog is never what someone editing a diagram wants.
        event.preventDefault()
        void save()
      } else if (mod && key === 'z' && !event.shiftKey) {
        event.preventDefault()
        void undo()
      } else if (mod && ((key === 'z' && event.shiftKey) || key === 'y')) {
        event.preventDefault()
        void redo()
      } else if ((event.key === 'Delete' || event.key === 'Backspace') && selectedLink) {
        event.preventDefault()
        deleteRelationship(selectedLink)
      } else if ((event.key === 'Delete' || event.key === 'Backspace') && selected.length > 0) {
        // The easiest key to hit by accident takes things off the diagram, never out of the
        // repository.
        event.preventDefault()
        removeFromView(selected)
      } else if (event.key === 'Escape') {
        setContext(null)
        setMenuOpen(false)
        setConnecting(false)
        setConnectFrom(null)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [undo, redo, save, selected, selectedLink, deleteRelationship, removeFromView])

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
            onPickUp={(type: ElementType) => setDropping({ type })}
            profiles={profiles}
            onPickUpProfile={(name) =>
              setDropping({ type: metamodel.elementTypes[0], profile: name })
            }
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
        {/*
          Twelve controls in one row, wrapping onto a second. Grouped now by what each belongs to:
          what the view is, what draws, and what is rare. The three delete buttons are gone from
          here entirely — they live on the right-click menu and on Delete, where they already
          were, and two of the three were permanently greyed out anyway.
        */}
        <div className="ag-toolbar">
          <strong className="ag-view-name">{view.data?.name ?? 'No view open'}</strong>

          <button
            type="button"
            className={dirty ? 'ag-tool-active' : ''}
            onClick={() => void save()}
            disabled={!dirty || saveLayout.isPending}
            title="Save this view's arrangement (Ctrl+S)"
          >
            {saveLayout.isPending ? 'Saving…' : dirty ? 'Save *' : 'Saved'}
          </button>
          <button
            type="button"
            onClick={() => void discard()}
            disabled={!dirty}
            title="Throw away the unsaved arrangement and go back to the saved one"
          >
            Discard
          </button>

          <span className="ag-toolbar-divider" />

          <button
            type="button"
            className={connecting ? 'ag-tool-active' : ''}
            onClick={() => {
              setConnecting((on) => !on)
              setConnectFrom(null)
              setMessage(null)
            }}
            disabled={!viewKey}
            title="Connect: click the source, then the target. Click empty space to make a new element."
          >
            {connecting ? 'Connecting…' : 'Connect'}
          </button>
          <button type="button" onClick={() => void undo()} disabled={history.undoStack.length === 0} title="Undo (Ctrl+Z)">
            Undo
          </button>
          <button type="button" onClick={() => void redo()} disabled={history.redoStack.length === 0} title="Redo (Ctrl+Shift+Z)">
            Redo
          </button>

          {/* Settings of the view rather than daily actions, so they are one click further away. */}
          <div className="ag-menu">
            <button
              type="button"
              className={menuOpen ? 'ag-tool-active' : ''}
              onClick={() => setMenuOpen((open) => !open)}
              disabled={!viewKey}
              title="More"
              aria-label="More"
            >
              ⋯
            </button>
            {menuOpen && (
              <>
                <div className="ag-context-backdrop" onClick={() => setMenuOpen(false)} />
                <div className="ag-menu-popover" role="menu">
                  <label className="ag-field">
                    <span>Lines</span>
                    <select
                      className="ag-router-select"
                      value={router}
                      onChange={(event) => chooseRouter(event.target.value as ConnectionRouter)}
                    >
                      <option value="straight">Straight</option>
                      <option value="orthogonal">Orthogonal</option>
                    </select>
                  </label>
                  <button
                    type="button"
                    onClick={() => {
                      setMenuOpen(false)
                      if (!view.data) {
                        return
                      }
                      void autoLayout(onView, view.data.relationships).then((laid) => {
                        setPositions(laid)
                        rememberChange(laid)
                        setGeometryVersion((version) => version + 1)
                        setFitToken(`auto:${Date.now()}`)
                      })
                    }}
                  >
                    Auto-layout
                  </button>
                </div>
              </>
            )}
          </div>

          <span className="ag-toolbar-spacer" />
          <span className="ag-muted">
            {onView.length} on view · {allElements.length} in repository
          </span>
        </div>

        {message && (
          <div className={`ag-banner ag-banner-${message.kind}`} role="status">
            <span>{message.text}</span>
            <button type="button" className="ag-banner-close" onClick={() => setMessage(null)} aria-label="Dismiss">
              ×
            </button>
          </div>
        )}

        <div className="ag-canvas">
          {viewKey ? (
            <Canvas
              elements={canvasElements}
              links={canvasLinks}
              selected={selected}
              editingKey={editingKey}
              onSelect={setSelected}
              onSelectLink={setSelectedLink}
              selectedLink={selectedLink}
              onGeometry={onGeometry}
              onGeometryStart={onGeometryStart}
              onGeometryEnd={onGeometryEnd}
              onNest={onNest}
              connecting={connecting}
              connectFrom={connectFrom}
              onConnectPick={onConnectPick}
              onMagic={(source, x, y, at) => {
                setConnectFrom(null)
                setConnecting(false)
                setMessage(null)
                setMagic({ source, x, y, at })
              }}
              onContextMenu={(elementKey, x, y) => setContext({ elementKey, x, y })}
              onBlankClick={() => setContext(null)}
              onCommitName={(key, name) => {
                setEditingKey(null)
                if (name) {
                  apply.mutate({ commands: [{ op: 'rename-element', key, name }], label: `Rename to ${name}` })
                }
              }}
              onCancelEdit={() => setEditingKey(null)}
              router={router}
              dropping={dropping != null}
              onDropHere={(at) => {
                if (!dropping) {
                  return
                }
                // Centred on the cursor: it was pointing at where the element should be, not at
                // the pixel its top-left corner should occupy.
                const snap = (value: number) => Math.round(value / GRID) * GRID
                placeAt.current[uniqueKey(dropping.profile ?? dropping.type.name, byKey)] = {
                  x: snap(at.x - NODE_WIDTH / 2),
                  y: snap(at.y - NODE_HEIGHT / 2),
                }
                if (dropping.profile) {
                  createElement(dropping.profile, dropping.profile)
                } else {
                  createElement(dropping.type.concept, dropping.type.name)
                }
                setDropping(null)
                setDropAt(null)
              }}
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
        onDeleteRelationship={deleteRelationship}
      />
      )}

      {dropping && dropAt && (
        <div className="ag-drag-ghost" style={{ left: dropAt.x + 12, top: dropAt.y + 12 }}>
          {dropping.profile ?? dropping.type.name}
        </div>
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

      {nesting && (
        <RelationshipPicker
          x={nesting.x}
          y={nesting.y}
          sourceName={byKey[nesting.container]?.name ?? nesting.container}
          targetName={byKey[nesting.child]?.name ?? nesting.child}
          reason={
            `Drawn inside on this view only. What does it mean? Pick a relationship, or press ` +
            `Escape to leave them merely nested.`
          }
          permitted={nesting.permitted}
          onPick={(type) => {
            const { container, child } = nesting
            setNesting(null)
            connect(container, child, type)
          }}
          onCancel={() => setNesting(null)}
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

/** Geometry as the layout endpoint wants it. Missing sizes fall back to the default box. */
function layoutNodes({ positions, sizes, parents }: Geometry) {
  return Object.entries(positions).map(([element, position]) => ({
    element,
    x: position.x,
    y: position.y,
    width: sizes[element]?.width ?? NODE_WIDTH,
    height: sizes[element]?.height ?? NODE_HEIGHT,
    parent: parents[element] ?? null,
  }))
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
      // Not "someone else": the commonest cause is this browser holding a stale head — another
      // tab of your own, or a change that landed while this one was thinking. Say what happened
      // and what to do, and do not accuse a colleague who may not exist.
      return 'This repository moved on since your last change. The latest has just been fetched — try again.'
    }
    return error.message
  }
  return error instanceof Error ? error.message : String(error)
}
