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
  ViewDetail,
  ViewFilter,
} from '../api/types.ts'
import { Metamodel } from '../metamodel/Metamodel.ts'
import Canvas, { type CanvasElement, type CanvasLink, type Geometry } from './Canvas.tsx'
import ContextMenu, { type ContextTarget } from './ContextMenu.tsx'
import Filters from './Filters.tsx'
import { uniqueKey } from './keys.ts'
import LinkMenu, { type LinkTarget } from './LinkMenu.tsx'
import Explorer from './Explorer.tsx'
import FolderTree, { type TreeItem } from './FolderTree.tsx'
import MagicMenu from './MagicMenu.tsx'
import Palette from './Palette.tsx'
import Properties from './Properties.tsx'
import Splitter from './Splitter.tsx'
import RelationshipPicker from './RelationshipPicker.tsx'
import { c4Layout } from './c4.ts'
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
  const [placements, setPlacements] = useState<Record<string, string>>({})
  const placementsRef = useRef<Record<string, string>>({})
  const [linkContext, setLinkContext] = useState<LinkTarget | null>(null)
  /**
   * Elements taken with Ctrl+C, by key.
   *
   * Keys, not copies of the elements: pasting on another view means "show these here", which is
   * membership and nothing more. Making a second Payment API is a different act, and it has its
   * own gesture (ADR-0008 — the repository is the thing, diagrams are windows on it).
   */
  const [clipboard, setClipboard] = useState<string[]>([])
  const [exportAs, setExportAs] = useState<{ format: 'svg' | 'png'; name: string; token: number } | null>(null)
  /** A C4 view just created and not yet arranged: sizes and nesting land when it opens. */
  const projected = useRef<{ view: string; layout: ReturnType<typeof c4Layout> } | null>(null)
  const exportToken = useRef(0)
  const [hiddenOpen, setHiddenOpen] = useState(false)
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
  /**
   * Which element each box on the view draws, by placement id.
   *
   * A box is a *placement*, not an element: the same element may be drawn several times on one
   * view (ADR-0014). Membership — whether the element is on the view at all — stays versioned and
   * element-keyed; how often it is drawn is layout, and lives here.
   *
   * Everything below is therefore keyed by placement id, not by element key.
   */
  /** Every box drawing this element, in the order they were made. */
  const placementsOf = useCallback(
    (element: string) => Object.keys(placements).filter((node) => placements[node] === element),
    [placements],
  )
  /** What the selection is about, with each element named once however often it is drawn. */
  const selectedElements = useMemo(
    () => [...new Set(selected.map((node) => placements[node]).filter((key): key is string => key != null))],
    [selected, placements],
  )

  /**
   * Where the element in hand is used, asked of the server rather than inferred.
   *
   * A view lists its own elements; nothing on the client knows the other twenty views without
   * fetching them all. And this is the answer that matters before a deletion: "it will go from
   * these four diagrams" is a decision, "it will go from everywhere" is a leap of faith.
   */
  // From state, never from the ref beside it: a value read out of a ref during render does not
  // make the render happen again when it changes, which is how a panel ends up describing the
  // element that was selected a moment ago.
  const usageKey = context?.element ?? (selectedElements.length === 1 ? selectedElements[0] : null)
  const usage = useQuery({
    queryKey: ['usage', workspace, usageKey],
    enabled: usageKey != null,
    queryFn: () => api.usage(workspace, usageKey!),
  })
  const usageViews = usage.data && usage.isSuccess && usageKey ? usage.data.views : null

  /** Absolute geometry per placement: where it is and, once resized, how big. */
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
  const rememberLines = useCallback(
    (next: Record<string, { key: string; source: string | null; target: string | null }>) => {
      linesRef.current = next
      setLines(next)
    },
    [],
  )
  const rememberPlacements = useCallback((next: Record<string, string>) => {
    placementsRef.current = next
    setPlacements(next)
  }, [])
  /** Which element is drawn over which, and the request that changes it. */
  const [order, setOrder] = useState<Record<string, number>>({})
  const orderRef = useRef<Record<string, number>>({})
  const [restack, setRestack] = useState<{ keys: string[]; to: 'front' | 'back'; token: number } | null>(null)
  /**
   * Which box each end of a line is attached to, where the architect has said.
   *
   * Empty for every line on a view that draws nothing twice — the ends follow the only boxes there
   * are. It fills up when somebody drags an end onto a second copy (ADR-0014).
   */
  const [lines, setLines] = useState<Record<string, { key: string; source: string | null; target: string | null }>>(
    {},
  )
  const linesRef = useRef<Record<string, { key: string; source: string | null; target: string | null }>>({})
  /**
   * Relationships this view declines to draw.
   *
   * Not deleted: the model still asserts them and every other view still draws them. A diagram is
   * allowed to leave something out — nesting already does exactly that (ADR-0011) — but it has to
   * say so, which is what the counter in the toolbar is for.
   */
  const [hidden, setHidden] = useState<string[]>([])
  const hiddenRef = useRef<string[]>([])
  /**
   * Where each line bends, by relationship key.
   *
   * Layout, not model: the relationship is the same relationship whichever way its line is drawn,
   * and the same one is drawn on other views with other boxes in the way (ADR-0003).
   */
  const [vertices, setVertices] = useState<Record<string, Position[]>>({})
  const verticesRef = useRef<Record<string, Position[]>>({})
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
  const rememberOrder = useCallback((next: Record<string, number>) => {
    orderRef.current = next
    setOrder(next)
  }, [])
  const rememberHidden = useCallback((next: string[]) => {
    hiddenRef.current = next
    setHidden(next)
  }, [])
  const rememberVertices = useCallback((next: Record<string, Position[]>) => {
    verticesRef.current = next
    setVertices(next)
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
  const applyStoredLayout = useCallback(
    (data: ViewDetail, withDraft: boolean) => {
      const stored: Record<string, { x: number; y: number }> = {}
      const storedSizes: Record<string, { width: number; height: number }> = {}
      const storedParents: Record<string, string | null> = {}
      const storedOrder: Record<string, number> = {}
      const storedPlacements: Record<string, string> = {}
      for (const node of data.layout) {
        storedPlacements[node.id] = node.element
        stored[node.id] = { x: node.x, y: node.y }
        storedParents[node.id] = node.parent
        if (node.z != null) {
          storedOrder[node.id] = node.z
        }
        if (node.width && node.height) {
          storedSizes[node.id] = { width: node.width, height: node.height }
        }
      }
      const storedVertices: Record<string, Position[]> = {}
      const storedLines: Record<string, { key: string; source: string | null; target: string | null }> = {}
      for (const link of data.layoutLinks) {
        storedVertices[link.id] = link.vertices
        storedLines[link.id] = { key: link.key, source: link.sourceNode, target: link.targetNode }
      }
      const draft = withDraft ? readDraft(workspace, data.key) : null
      rememberPlacements(draft ? { ...storedPlacements, ...draft.placements } : storedPlacements)
      setPositions(draft ? { ...stored, ...draft.positions } : stored)
      setSizes(draft ? { ...storedSizes, ...draft.sizes } : storedSizes)
      rememberParents(draft ? { ...storedParents, ...draft.parents } : storedParents)
      rememberOrder(draft ? { ...storedOrder, ...draft.order } : storedOrder)
      rememberHidden(draft?.hidden ?? data.hidden)
      rememberVertices(draft ? { ...storedVertices, ...draft.vertices } : storedVertices)
      rememberLines(draft ? { ...storedLines, ...draft.lines } : storedLines)
      setRouter(draft?.router ?? data.router)
      routerRef.current = draft?.router ?? data.router
      setDirty(draft != null)
      setGeometryFor(data.key)
      setGeometryVersion((version) => version + 1)
    },
    [
      workspace,
      rememberPlacements,
      rememberParents,
      rememberOrder,
      rememberHidden,
      rememberVertices,
      rememberLines,
    ],
  )

  useEffect(() => {
    if (view.data) {
      applyStoredLayout(view.data, true)
    }
  }, [view.data, applyStoredLayout])

  const refresh = useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['graph', workspace] }),
      queryClient.invalidateQueries({ queryKey: ['repository', workspace] }),
      queryClient.invalidateQueries({ queryKey: ['views', workspace] }),
      queryClient.invalidateQueries({ queryKey: ['view', workspace] }),
      queryClient.invalidateQueries({ queryKey: ['usage', workspace] }),
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
      const inverse = invert(commands, {
        elements: byKey,
        relationships: relationshipsByKey,
        view: viewKey ? { key: viewKey, elements: onViewKeys } : null,
      })
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
    mutationFn: (
      geometry: Geometry & {
        router: ConnectionRouter
        hidden: string[]
        lines: Record<string, { key: string; source: string | null; target: string | null }>
      },
    ) =>
      api.saveLayout(
        workspace,
        viewKey!,
        layoutNodes(geometry),
        geometry.router,
        geometry.hidden,
        layoutLinks(geometry.lines, geometry.vertices),
      ),
    /**
     * Tells the cache what was just written.
     *
     * Without this a view saved and reopened came back as it had been *before* the save: the draft
     * is cleared on save, so the canvas is drawn from the cached copy of the view, and that copy
     * still held the old layout for as long as it stayed fresh. Which made it look intermittent —
     * within the stale window the old arrangement, after it the new one.
     *
     * And it was worse than a redraw: the next drag started from those stale positions and the
     * next save wrote them over the good ones. Model commands never had the problem because they
     * go through `refresh()`; only the layout endpoint wrote to the server and told nobody.
     *
     * Patched rather than invalidated, because we know exactly what the server now holds — it is
     * what we just sent — and a refetch would put a round trip between saving and being able to
     * trust what is on screen.
     */
    onSuccess: (_result, geometry) => {
      if (!viewKey) {
        return
      }
      queryClient.setQueryData<ViewDetail>(['view', workspace, viewKey], (current) =>
        current
          ? {
              ...current,
              layout: layoutNodes(geometry),
              layoutLinks: layoutLinks(geometry.lines, geometry.vertices),
              hidden: geometry.hidden,
              router: geometry.router,
            }
          : current,
      )
    },
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
        placements: placementsRef.current,
        positions: next,
        sizes: nextSizes ?? sizes,
        parents: nextParents ?? parentsRef.current,
        order: orderRef.current,
        hidden: hiddenRef.current,
        vertices: verticesRef.current,
        lines: linesRef.current,
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
    await saveLayout.mutateAsync({
      placements: placementsRef.current,
      positions,
      sizes,
      parents: parentsRef.current,
      order: orderRef.current,
      hidden: hiddenRef.current,
      vertices: verticesRef.current,
      lines: linesRef.current,
      router,
    })
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
    await queryClient.invalidateQueries({ queryKey: ['view', workspace] })
    /*
     * Applied here rather than left to the refetch.
     *
     * The unsaved arrangement lives in this component's state, not in the query cache, so the
     * refetch comes back byte-identical to what was already cached — react-query hands back the
     * same object, `view.data` never changes, the effect that loads a layout never re-runs, and
     * Discard threw away the draft while leaving the canvas exactly as the user had dragged it.
     * Which is the worst of both: nothing to save, and nothing put back.
     */
    const current = queryClient.getQueryData<ViewDetail>(['view', workspace, viewKey])
    if (current) {
      applyStoredLayout(current, false)
    }
  }, [viewKey, workspace, queryClient, applyStoredLayout])

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
    // An element on the view with no box yet: it has just been created, pasted or projected.
    const drawn = new Set(Object.values(placementsRef.current))
    const missing = onView.filter((element) => !drawn.has(element.key))
    // And boxes whose element has left the view: their placement goes with it.
    const orphaned = Object.keys(placementsRef.current).filter(
      (node) => !onViewKeys.has(placementsRef.current[node]),
    )
    if (missing.length === 0 && orphaned.length === 0) {
      return
    }
    const withPlacements = { ...placementsRef.current }
    for (const node of orphaned) {
      delete withPlacements[node]
    }
    const nodeFor = new Map<string, string>()
    for (const element of missing) {
      const node = crypto.randomUUID()
      nodeFor.set(element.key, node)
      withPlacements[node] = element.key
    }
    rememberPlacements(withPlacements)
    // Auto-layout only when nothing has said where these should go. A projection arranges its own
    // diagram — a boundary with its parts inside it — and handing that to a layered algorithm
    // would produce a correct drawing in which nothing is obviously the subject.
    const unrequested = missing.filter((element) => !placeAt.current[element.key])
    if (Object.keys(positions).length === 0 && unrequested.length === missing.length) {
      void autoLayout(onView, view.data.relationships).then((laid) => {
        const byNode: Record<string, Position> = {}
        for (const [element, at] of Object.entries(laid)) {
          const node = nodeFor.get(element)
          if (node) {
            byNode[node] = at
          }
        }
        setPositions(byNode)
        rememberChange(byNode)
      })
    } else {
      let taken = positions
      const placed: Record<string, { x: number; y: number }> = {}
      for (const element of missing) {
        // A place the user pointed at beats the tidy grid every time.
        const asked = placeAt.current[element.key]
        delete placeAt.current[element.key]
        const position = asked ?? nextFreePosition(taken, sizes)
        const node = nodeFor.get(element.key)!
        placed[node] = position
        taken = { ...taken, [node]: position }
      }
      const next = { ...positions, ...placed }
      setPositions(next)
      rememberChange(next)
    }
  }, [view.data, onView, onViewKeys, positions, sizes, geometryFor, rememberChange, rememberPlacements])

  // Fitting waits for real coordinates. Auto-layout resolves through ELK after the canvas mounts,
  // and refitting before that opens the view zoomed into an empty corner.
  useEffect(() => {
    if (!viewKey || fittedFor.current === viewKey || onView.length === 0) {
      return
    }
    if (Object.keys(placements).every((node) => positions[node])) {
      fittedFor.current = viewKey
      setFitToken(`${viewKey}:${onView.length}`)
    }
  }, [viewKey, onView, placements, positions])

  /** Which boxes on this view contain others. A container is a placement, not an element. */
  const containers = useMemo(() => {
    const withChildren = new Set<string>()
    for (const node of Object.keys(placements)) {
      const parent = parents[node]
      if (parent && placements[parent]) {
        withChildren.add(parent)
      }
    }
    return withChildren
  }, [placements, parents])

  const canvasElements: CanvasElement[] = useMemo(
    () =>
      Object.entries(placements)
        .map(([node, elementKey]): CanvasElement | null => {
          const element = byKey[elementKey]
          if (!element || !onViewKeys.has(elementKey)) {
            return null
          }
          const type = metamodel.elementType(element.conceptType)
          const at = positions[node] ?? { x: 0, y: 0 }
          const size = sizes[node]
          const container = containers.has(node)
          const parent = parents[node]
          return {
            key: node,
            element: elementKey,
            name: element.name,
            typeLabel: element.profile ?? type?.name ?? element.conceptType,
            layer: type?.layer ?? 'other',
            specialised: element.profile != null,
            concept: element.conceptType,
            // From this view's layout, and only when the container is drawn too: a parent nobody
            // can see is not one.
            parent: parent && placements[parent] ? parent : null,
            x: at.x,
            y: at.y,
            width: size?.width ?? (container ? 320 : NODE_WIDTH),
            height: size?.height ?? (container ? 200 : NODE_HEIGHT),
            z: order[node] ?? null,
          }
        })
        .filter((element): element is CanvasElement => element !== null),
    [placements, byKey, onViewKeys, positions, sizes, parents, order, containers, metamodel],
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
    (relationship: ModelRelationship, sourceNode: string, targetNode: string) => {
      if (metamodel.relationshipType(relationship.type)?.category !== 'structural') {
        return false
      }
      // Between these two boxes, not between these two elements: a copy of the child may stand
      // alone beside the container, and the line to that copy is the only one saying anything.
      return parents[targetNode] === sourceNode || parents[sourceNode] === targetNode
    },
    [parents, metamodel],
  )

  /**
   * What the counter reports: lines this view would draw right now if it were not hiding them.
   *
   * The stored set is deliberately wider — a relationship whose endpoint has left the view, or
   * whose element is temporarily elsewhere, keeps its hidden state for when it comes back. Only
   * counting what is actually being withheld keeps "2 hidden" from meaning a relationship someone
   * deleted from the model last week.
   */
  const hiddenOnView = useMemo(
    () => hidden.filter((key) => (view.data?.relationships ?? []).some((r) => r.key === key)),
    [hidden, view.data],
  )

  /**
   * One kind of line, and it stands for a relationship the model states (ADR-0013).
   *
   * Implied relationships used to be drawn faintly beside them. They are not drawn at all now,
   * which is a decision with a cost — a context view of a system whose parts talk to the outside
   * shows no line to the outside — and the cost is written down where the decision is.
   */
  /**
   * Every relationship on the view has at least one line, and a line whose relationship left the
   * model goes with it.
   *
   * Materialised in state rather than computed per render, because a line is a thing with an
   * identity now: a second one can exist, and which one carries the bendpoints matters (V15).
   */
  useEffect(() => {
    if (!view.data || geometryFor !== view.data.key) {
      return
    }
    const known = new Set((view.data.relationships ?? []).map((relationship) => relationship.key))
    const next = { ...linesRef.current }
    let changed = false
    for (const [lineId, line] of Object.entries(next)) {
      if (!known.has(line.key)) {
        delete next[lineId]
        changed = true
      }
    }
    const drawn = new Set(Object.values(next).map((line) => line.key))
    for (const key of known) {
      if (!drawn.has(key)) {
        next[crypto.randomUUID()] = { key, source: null, target: null }
        changed = true
      }
    }
    if (changed) {
      rememberLines(next)
    }
  }, [view.data, geometryFor, lines, rememberLines])

  /**
   * Which box each end of a line attaches to.
   *
   * Chosen by the architect where they have said so — dragging an end onto another copy is how —
   * and otherwise the first box drawing that element. With one box per element, which is every
   * view until somebody draws a second, this is exactly what it always was.
   */
  const endsOf = useCallback(
    (line: { source: string | null; target: string | null }, source: string, target: string) => {
      const at = (node: string | null, element: string) =>
        node && placements[node] === element ? node : placementsOf(element)[0]
      return { source: at(line.source, source), target: at(line.target, target) }
    },
    [placements, placementsOf],
  )

  /**
   * One cell per line, not per relationship — a relationship may be drawn by several (V15). Each
   * line stands for a relationship the model states (ADR-0013).
   */
  const canvasLinks: CanvasLink[] = useMemo(
    () =>
      Object.entries(lines)
        .map(([lineId, line]): CanvasLink | null => {
          const relationship = relationshipsByKey[line.key]
          if (!relationship || hidden.includes(line.key)) {
            return null
          }
          const ends = endsOf(line, relationship.source, relationship.target)
          if (!ends.source || !ends.target || expressedByNesting(relationship, ends.source, ends.target)) {
            return null
          }
          return {
            key: lineId,
            relationship: line.key,
            source: ends.source,
            target: ends.target,
            sourceElement: relationship.source,
            targetElement: relationship.target,
            label: relationship.name ?? relationship.type,
            type: relationship.type,
          }
        })
        .filter((link): link is CanvasLink => link !== null),
    [lines, relationshipsByKey, endsOf, expressedByNesting, hidden],
  )

  /** Live geometry from a drag or resize. The save is debounced; the state is not. */
  const onGeometry = useCallback(
    (next: Geometry) => {
      setPositions(next.positions)
      setSizes(next.sizes)
      rememberOrder(next.order)
      rememberVertices(next.vertices)
      rememberChange(next.positions, next.sizes)
    },
    [rememberChange, rememberOrder, rememberVertices],
  )

  /**
   * A drag becomes one undoable action, recorded on release rather than per frame.
   *
   * Moving a box is not a model change and produces no commit (ADR-0003), but it is very much
   * something the user just did, so it belongs on the same stack as everything else.
   */
  /**
   * Asks the canvas to restack, rather than computing new z values here.
   *
   * The token is what makes a repeat of the same request land: bringing the same element forward
   * twice is two actions, and an object equal to the previous one would leave the effect asleep.
   */
  const restackToken = useRef(0)
  const restackTo = useCallback((keys: string[], to: 'front' | 'back') => {
    restackToken.current += 1
    setRestack({ keys, to, token: restackToken.current })
  }, [])

  /**
   * Leaves a line off this view without unsaying it.
   *
   * Layout, so it goes into the draft and reaches the server on Save, like the coordinates it
   * travels with (ADR-0003). The relationship stays in the model and on every other view.
   */
  const hideLink = useCallback(
    (key: string) => {
      rememberHidden([...hiddenRef.current.filter((k) => k !== key), key])
      rememberChange(positions)
      setSelectedLink(null)
    },
    [rememberHidden, rememberChange, positions],
  )

  const showLink = useCallback(
    (key: string | null) => {
      rememberHidden(key === null ? [] : hiddenRef.current.filter((k) => k !== key))
      rememberChange(positions)
    },
    [rememberHidden, rememberChange, positions],
  )

  /**
   * Draws this element a second time on this view.
   *
   * Not a copy of the element — a second box showing the same one (ADR-0014). Membership does not
   * change and nothing is committed: how often an element is drawn is layout, like where.
   *
   * The point of it is lines: a shared component drawn once pulls every line towards it and across
   * the diagram, and drawn beside each of its users it does not. So the new box arrives empty of
   * lines, and the architect moves the ones they want onto it by dragging an end.
   */
  const drawAgain = useCallback(
    (node: string) => {
      const element = placementsRef.current[node]
      if (!element || !viewKey) {
        return
      }
      const copy = crypto.randomUUID()
      const at = positions[node] ?? { x: 40, y: 40 }
      const nextPositions = { ...positions, [copy]: { x: at.x + GRID * 4, y: at.y + GRID * 4 } }
      const size = sizes[node]
      const nextSizes = size ? { ...sizes, [copy]: size } : sizes
      rememberPlacements({ ...placementsRef.current, [copy]: element })
      setPositions(nextPositions)
      setSizes(nextSizes)
      rememberChange(nextPositions, nextSizes)
      setGeometryVersion((version) => version + 1)
      // Selected, so the next drag moves the new box rather than the one it came from.
      setSelected([copy])
    },
    [viewKey, positions, sizes, rememberPlacements, rememberChange],
  )

  /** A line whose end was dragged onto another box of the same element. */
  const onLinkEnds = useCallback(
    (lineId: string, source: string, target: string) => {
      const line = linesRef.current[lineId]
      if (!line) {
        return
      }
      rememberLines({ ...linesRef.current, [lineId]: { ...line, source, target } })
      rememberChange(positions)
    },
    [rememberLines, rememberChange, positions],
  )

  /**
   * Draws this relationship a second time — a second line, to reach a second copy (V15).
   *
   * Born with a bendpoint, deliberately: a new line with default ends lies exactly on top of the
   * one it came from, and a line nobody can see or grab is not a feature. The kink makes it
   * visible; the architect then drags its ends where they want them.
   */
  const drawLineAgain = useCallback(
    (lineId: string) => {
      const line = linesRef.current[lineId]
      const relationship = line ? relationshipsByKey[line.key] : null
      if (!line || !relationship) {
        return
      }
      const ends = endsOf(line, relationship.source, relationship.target)
      const from = ends.source ? positions[ends.source] : null
      const to = ends.target ? positions[ends.target] : null
      const middle =
        from && to
          ? { x: Math.round((from.x + to.x) / 2) + 60, y: Math.round((from.y + to.y) / 2) + 60 }
          : { x: 200, y: 200 }
      const copy = crypto.randomUUID()
      rememberLines({ ...linesRef.current, [copy]: { key: line.key, source: line.source, target: line.target } })
      rememberVertices({ ...verticesRef.current, [copy]: [middle] })
      rememberChange(positions)
      setGeometryVersion((version) => version + 1)
    },
    [relationshipsByKey, endsOf, positions, rememberLines, rememberVertices, rememberChange],
  )

  /** Removes one line of a relationship drawn several times. The last line is not removable. */
  const removeLine = useCallback(
    (lineId: string) => {
      const line = linesRef.current[lineId]
      if (!line) {
        return
      }
      const siblings = Object.values(linesRef.current).filter((other) => other.key === line.key)
      if (siblings.length < 2) {
        return
      }
      const next = { ...linesRef.current }
      delete next[lineId]
      const nextVertices = { ...verticesRef.current }
      delete nextVertices[lineId]
      rememberLines(next)
      rememberVertices(nextVertices)
      rememberChange(positions)
      setSelectedLink(null)
      setGeometryVersion((version) => version + 1)
    },
    [rememberLines, rememberVertices, rememberChange, positions],
  )

  const onGeometryStart = useCallback(() => {
    dragOrigin.current = positions
  }, [positions])

  const onGeometryEnd = useCallback(
    (next: Geometry) => {
      const before = dragOrigin.current
      dragOrigin.current = null
      setPositions(next.positions)
      setSizes(next.sizes)
      rememberOrder(next.order)
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
    [viewKey, history, rememberChange, rememberOrder],
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
    (node: string, parentNode: string | null, next: Geometry) => {
      setPositions(next.positions)
      setSizes(next.sizes)
      rememberParents(next.parents)
      // Recorded like any other change. The refetch that the offered relationship triggers no
      // longer threatens it: the draft is laid over whatever the server returns, so the nesting
      // survives without having to be raced to the server first.
      rememberChange(next.positions, next.sizes, next.parents)
      if (!parentNode) {
        return
      }
      // Boxes nest; the relationship the nesting might express is between the elements they draw.
      const parent = next.placements[parentNode]
      const key = next.placements[node]
      const container = parent ? byKey[parent] : null
      const child = key ? byKey[key] : null
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
      // The box that was just dropped, so the question appears beside it rather than mid-screen.
      const dropped = document.querySelector(`.ag-node[data-node="${CSS.escape(node)}"]`)
      const box = dropped?.getBoundingClientRect()
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

  /**
   * Ctrl+C, then Ctrl+V on another view: the same elements, shown in a second place.
   *
   * Not new elements — that is what duplicating is for. This is the gesture for the product's
   * central claim, that one element lives on many views (ADR-0009), and it is the fastest way to
   * build a second diagram out of a first.
   */
  const copySelection = useCallback(() => {
    if (selectedElements.length === 0) {
      return
    }
    setClipboard(selectedElements)
    setMessage({
      kind: 'info',
      text: `${selectedElements.length} element${
        selectedElements.length === 1 ? '' : 's'
      } copied. Ctrl+V shows them on another view; Ctrl+D makes new ones.`,
    })
  }, [selectedElements])

  const pasteHere = useCallback(() => {
    if (!viewKey || clipboard.length === 0) {
      return
    }
    const missing = clipboard.filter((key) => byKey[key] && !onViewKeys.has(key))
    if (missing.length === 0) {
      setMessage({
        kind: 'info',
        text: 'Those elements are already on this view. Ctrl+D makes new ones instead.',
      })
      return
    }
    apply.mutate({
      commands: missing.map((element) => ({ op: 'add-to-view', key: viewKey, element })),
      label: `Show ${missing.length} element${missing.length === 1 ? '' : 's'}`,
    })
  }, [viewKey, clipboard, byKey, onViewKeys, apply])

  /**
   * Ctrl+D: another element like this one, in the repository as well as on the diagram.
   *
   * Documentation and properties come along — a copy that quietly dropped them would be a trap —
   * but relationships do not. Duplicating two connected elements copies the elements; asserting
   * new relationships between the copies is a claim about the architecture that nobody made.
   */
  const duplicateSelection = useCallback(() => {
    if (!viewKey || selected.length === 0) {
      return
    }
    const commands: Command[] = []
    const created: string[] = []
    // Grows as it goes: two copies of the same element in one gesture must not claim the same key.
    const taken: Record<string, unknown> = { ...byKey }
    for (const node of selected) {
      const sourceKey = placements[node]
      const element = sourceKey ? byKey[sourceKey] : null
      if (!element) {
        continue
      }
      const name = `${element.name} (copy)`
      const key = uniqueKey(name, taken)
      taken[key] = true
      created.push(key)
      const at = positions[node]
      if (at) {
        placeAt.current[key] = { x: at.x + GRID * 2, y: at.y + GRID * 2 }
      }
      commands.push({ op: 'create-element', key, type: element.profile ?? element.conceptType, name })
      commands.push({ op: 'add-to-view', key: viewKey, element: key })
      if (element.documentation) {
        commands.push({ op: 'set-element-documentation', key, documentation: element.documentation })
      }
      for (const [property, value] of Object.entries(element.properties)) {
        commands.push({ op: 'set-element-property', key, property, value })
      }
    }
    if (commands.length > 0) {
      apply.mutate({ commands, label: `Duplicate ${selected.length} element${selected.length === 1 ? '' : 's'}` })
      // The copies are new elements, so their boxes do not exist yet; the placement effect makes
      // them when the commit comes back. Clearing the selection is honest until then.
      setSelected([])
    }
  }, [viewKey, selected, placements, byKey, positions, apply])

  /**
   * Align and distribute a multiple selection.
   *
   * Straight lines between boxes are what makes a diagram look drafted rather than dropped, and
   * the guides only help one element at a time. Pure layout: coordinates move, nothing commits
   * (ADR-0003), and the whole gesture is one entry on the undo stack.
   */
  const arrange = useCallback(
    (how: 'left' | 'centre' | 'right' | 'top' | 'middle' | 'bottom' | 'across' | 'down') => {
      if (selected.length < 2 || !viewKey) {
        return
      }
      const boxes = selected
        .map((key) => ({ key, at: positions[key], size: sizes[key] ?? { width: NODE_WIDTH, height: NODE_HEIGHT } }))
        .filter((box): box is { key: string; at: Position; size: Size } => box.at != null)
      if (boxes.length < 2) {
        return
      }
      const snap = (value: number) => Math.round(value / GRID) * GRID
      const left = Math.min(...boxes.map((box) => box.at.x))
      const right = Math.max(...boxes.map((box) => box.at.x + box.size.width))
      const top = Math.min(...boxes.map((box) => box.at.y))
      const bottom = Math.max(...boxes.map((box) => box.at.y + box.size.height))
      const next = { ...positions }

      if (how === 'across' || how === 'down') {
        // Even gaps between edges, not between centres: with boxes of different widths, evenly
        // spaced centres leave gaps that visibly are not even.
        const horizontal = how === 'across'
        const ordered = [...boxes].sort((a, b) => (horizontal ? a.at.x - b.at.x : a.at.y - b.at.y))
        const span = horizontal ? right - left : bottom - top
        const used = ordered.reduce((total, box) => total + (horizontal ? box.size.width : box.size.height), 0)
        const gap = (span - used) / (ordered.length - 1)
        let cursor = horizontal ? left : top
        for (const box of ordered) {
          next[box.key] = horizontal ? { ...box.at, x: snap(cursor) } : { ...box.at, y: snap(cursor) }
          cursor += (horizontal ? box.size.width : box.size.height) + gap
        }
      } else {
        for (const box of boxes) {
          const to: { x?: number; y?: number } = {
            left: { x: left },
            right: { x: right - box.size.width },
            centre: { x: (left + right) / 2 - box.size.width / 2 },
            top: { y: top },
            bottom: { y: bottom - box.size.height },
            middle: { y: (top + bottom) / 2 - box.size.height / 2 },
          }[how]
          next[box.key] = { x: snap(to.x ?? box.at.x), y: snap(to.y ?? box.at.y) }
        }
      }

      history.push({ kind: 'layout', view: viewKey, before: positions, after: next, label: 'Align' })
      setPositions(next)
      rememberChange(next)
      // The canvas holds its own geometry while mounted, so a change made above it has to say so.
      setGeometryVersion((version) => version + 1)
    },
    [selected, viewKey, positions, sizes, history, rememberChange],
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
   * The heavier half of the pair the line's menu offers. Its lighter half — hiding the line on
   * this view — leaves the model alone, so this one is reserved for the case where the
   * relationship should not exist at all (ADR-0011).
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
  /**
   * Takes boxes off the view.
   *
   * A box is a placement, and an element may have several (ADR-0014). Removing one of two copies
   * is layout and produces no commit — the element is still on the view, drawn once. Removing the
   * last one is membership, and that is a command like it always was.
   */
  const removeFromView = useCallback(
    (nodes: string[]) => {
      if (!viewKey || nodes.length === 0) {
        return
      }
      setContext(null)
      const remaining = { ...placementsRef.current }
      const leaving: string[] = []
      for (const node of nodes) {
        const element = remaining[node]
        if (!element) {
          continue
        }
        delete remaining[node]
        if (!Object.values(remaining).includes(element)) {
          leaving.push(element)
        }
      }
      rememberPlacements(remaining)
      rememberChange(positions)
      setSelected([])
      setGeometryVersion((version) => version + 1)
      if (leaving.length === 0) {
        return
      }
      apply.mutate({
        commands: leaving.map((element) => ({ op: 'remove-from-view', key: viewKey, element })),
        label: `Remove ${leaving.map((key) => byKey[key]?.name ?? key).join(', ')} from ${
          view.data?.name ?? viewKey
        }`,
      })
    },
    [viewKey, byKey, view.data, apply, positions, rememberChange, rememberPlacements],
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
   * A C4 diagram of one element, as a real view.
   *
   * The projection itself is computed and stored nowhere — no element carries a C4 type, because
   * whether something is a system or a container depends on where the boundary is drawn
   * ([ADR-0007](docs/adr/0007-c4-projection.md)). What is kept is an ordinary view: membership
   * versioned like any other, and an arrangement the architect is free to change afterwards.
   * That is the difference between a projection and a report — you can carry on working in it.
   */
  const createC4View = useCallback(
    async (focusKey: string, level: string) => {
      let projection
      try {
        projection = await api.c4Projection(workspace, focusKey, level)
      } catch (error) {
        setMessage({ kind: 'error', text: describe(error) })
        return
      }
      const name = `${byKey[focusKey]?.name ?? focusKey} — ${projection.levelName}`
      const key = uniqueKey(name, Object.fromEntries((views.data ?? []).map((v) => [v.key, true])))
      const commands: Command[] = [
        { op: 'create-view', key, name, viewpoint: 'layered' },
        ...projection.elements.map((element) => ({ op: 'add-to-view', key, element: element.key }) as Command),
      ]
      if (!(await applySilently(commands, `C4 ${projection.levelName} of ${name}`))) {
        return
      }
      const arranged = c4Layout(projection)
      // Positions are asked for before the view opens; the placement effect uses them instead of
      // laying the elements out as a graph of peers.
      for (const [element, position] of Object.entries(arranged.positions)) {
        placeAt.current[element] = position
      }
      projected.current = { view: key, layout: arranged }
      setViewKey(key)
      setMessage(
        projection.omitted.length === 0
          ? { kind: 'info', text: `${projection.levelName} view created.` }
          : {
              kind: 'info',
              // Said out loud, because a diagram that quietly leaves things out claims a
              // completeness it does not have (ADR-0007).
              text: `${projection.levelName} view created. ${projection.omitted.length} ${
                projection.omitted.length === 1 ? 'element has' : 'elements have'
              } no form in C4 and ${projection.omitted.length === 1 ? 'is' : 'are'} not shown: ${projection.omitted
                .slice(0, 4)
                .map((element) => `${element.name} (${element.type})`)
                .join(', ')}${projection.omitted.length > 4 ? '…' : ''}`,
            },
      )
    },
    [api, workspace, byKey, views.data, applySilently],
  )

  /**
   * Narrows what this view draws, without touching what belongs to it.
   *
   * A command, because the filter is part of the view's definition and worth attributing
   * (ADR-0003) — and applied on the server, which is the whole value of it: a view filtered to the
   * application layer derives the application-level lines running through the technology it no
   * longer draws.
   */
  const setFilters = useCallback(
    (filters: ViewFilter[]) => {
      if (!viewKey) {
        return
      }
      void applySilently([{ op: 'set-view-filters', key: viewKey, filters }], 'Filter the view')
    },
    [viewKey, applySilently],
  )

  /** The layers the metamodel knows, in the order it lists its types. */
  const layers = useMemo(() => {
    const seen: string[] = []
    for (const type of metamodel.elementTypes) {
      if (!seen.includes(type.layer)) {
        seen.push(type.layer)
      }
    }
    return seen
  }, [metamodel])

  /** Sizes and nesting for a projected view, once the view it belongs to is the one open. */
  useEffect(() => {
    const pending = projected.current
    if (!pending || !view.data || view.data.key !== pending.view || geometryFor !== view.data.key) {
      return
    }
    projected.current = null
    setSizes((current) => ({ ...current, ...pending.layout.sizes }))
    rememberParents({ ...parentsRef.current, ...pending.layout.parents })
    rememberChange(pending.layout.positions, pending.layout.sizes, {
      ...parentsRef.current,
      ...pending.layout.parents,
    })
    setGeometryVersion((version) => version + 1)
  }, [view.data, geometryFor, rememberParents, rememberChange])

  /**
   * Restores a set of coordinates and persists them. Undoing a move is itself not a commit.
   */
  const restoreLayout = useCallback(
    (positionsToRestore: Record<string, Position>) => {
      setPositions(positionsToRestore)
      rememberChange(positionsToRestore)
      // The canvas keeps its own geometry while it is mounted and only takes ours when this
      // changes. Without it, undoing a move updated the state and left the box where it was —
      // silently, because everything else about undo looked as if it had worked.
      setGeometryVersion((version) => version + 1)
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
      } else if (mod && key === 'c' && selected.length > 0) {
        event.preventDefault()
        copySelection()
      } else if (mod && key === 'v') {
        event.preventDefault()
        pasteHere()
      } else if (mod && key === 'd') {
        // The browser's bookmark dialog is never what someone editing a diagram wants either.
        event.preventDefault()
        duplicateSelection()
      } else if ((event.key === 'Delete' || event.key === 'Backspace') && selectedLink) {
        event.preventDefault()
        // The cell is a line; Delete on an extra line removes the line, on the last one the
        // relationship — the same escalation the element side has with boxes and membership.
        const line = linesRef.current[selectedLink]
        if (line && Object.values(linesRef.current).filter((other) => other.key === line.key).length > 1) {
          removeLine(selectedLink)
        } else if (line) {
          deleteRelationship(line.key)
        }
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
  }, [undo, redo, save, selected, selectedLink, deleteRelationship, removeLine, removeFromView, copySelection, pasteHere, duplicateSelection])

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
                  <Filters
                    layers={layers}
                    filters={view.data?.filters ?? []}
                    filtered={view.data?.filtered ?? []}
                    onChange={setFilters}
                  />

                  {/* The diagram as a file: needed by any document that embeds it, and the only
                      way a view leaves the product before phase 5 brings documentation. */}
                  <div className="ag-menu-row">
                    {(['svg', 'png'] as const).map((format) => (
                      <button
                        key={format}
                        type="button"
                        onClick={() => {
                          setMenuOpen(false)
                          exportToken.current += 1
                          setExportAs({ format, name: view.data?.name ?? 'diagram', token: exportToken.current })
                        }}
                      >
                        Export {format.toUpperCase()}
                      </button>
                    ))}
                  </div>
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

          {/*
            A diagram that leaves something out has to admit it. Hiding a line is legitimate —
            nesting hides one too — but a reader who cannot tell is being shown an incomplete
            picture as if it were the whole one.
          */}
          {hiddenOnView.length > 0 && (
            <div className="ag-menu">
              <button
                type="button"
                className="ag-hidden-count"
                onClick={() => setHiddenOpen((open) => !open)}
                title="Relationships this view does not draw"
              >
                {hiddenOnView.length} hidden
              </button>
              {hiddenOpen && (
                <>
                  <div className="ag-context-backdrop" onClick={() => setHiddenOpen(false)} />
                  <div className="ag-menu-popover ag-hidden-list" role="menu">
                    <h4>Not drawn on this view</h4>
                    <ul>
                      {hiddenOnView.map((key) => {
                        const relationship = relationshipsByKey[key]
                        return (
                          <li key={key}>
                            <span>
                              {byKey[relationship?.source ?? '']?.name ?? relationship?.source ?? key}
                              <span className="ag-muted"> {relationship?.type ?? ''} → </span>
                              {byKey[relationship?.target ?? '']?.name ?? relationship?.target ?? ''}
                            </span>
                            <button type="button" className="ag-link" onClick={() => showLink(key)}>
                              show
                            </button>
                          </li>
                        )
                      })}
                    </ul>
                    <button
                      type="button"
                      onClick={() => {
                        showLink(null)
                        setHiddenOpen(false)
                      }}
                    >
                      Show all
                    </button>
                  </div>
                </>
              )}
            </div>
          )}

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
              onConnectPick={(node) => {
                const element = placementsRef.current[node]
                if (element) {
                  onConnectPick(element)
                }
              }}
              onMagic={(source, x, y, at) => {
                // Already an element: the canvas hands back what it was given, and the connector
                // was started from an element rather than from one of its boxes.
                setConnectFrom(null)
                setConnecting(false)
                setMessage(null)
                setMagic({ source, x, y, at })
              }}
              onContextMenu={(node, x, y) => {
                const element = placementsRef.current[node]
                if (element) {
                  setContext({ node, element, x, y })
                }
              }}
              onLinkContextMenu={(line, x, y) => {
                const known = linesRef.current[line]
                if (known) {
                  setLinkContext({ line, key: known.key, x, y })
                }
              }}
              onLinkEnds={onLinkEnds}
              onBlankClick={() => setContext(null)}
              onCommitName={(element, name) => {
                setEditingKey(null)
                if (name) {
                  apply.mutate({
                    commands: [{ op: 'rename-element', key: element, name }],
                    label: `Rename to ${name}`,
                  })
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
              vertices={vertices}
              restack={restack}
              exportAs={exportAs}
              onExportFailed={(text) => setMessage({ kind: 'error', text })}
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
        element={selectedElements.length === 1 ? (byKey[selectedElements[0]] ?? null) : null}
        selectedCount={selected.length}
        metamodel={metamodel}
        profile={
          selectedElements.length === 1
            ? profiles.find((p) => p.name === byKey[selectedElements[0]]?.profile)
            : undefined
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
        // Given an element, select the box that draws it — the first, where there are several.
        onSelect={(key) => setSelected(placementsOf(key).slice(0, 1))}
        onDeleteRelationship={deleteRelationship}
        views={usageViews}
        currentView={viewKey}
        onOpenView={setViewKey}
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

      {linkContext && relationshipsByKey[linkContext.key] && (
        <LinkMenu
          target={linkContext}
          relationship={relationshipsByKey[linkContext.key]}
          lineCount={Object.values(lines).filter((line) => line.key === linkContext.key).length}
          onDrawAgain={() => {
            drawLineAgain(linkContext.line)
            setLinkContext(null)
          }}
          onRemoveLine={() => {
            removeLine(linkContext.line)
            setLinkContext(null)
          }}
          elementsByKey={byKey}
          onClose={() => setLinkContext(null)}
          onHide={() => {
            hideLink(linkContext.key)
            setLinkContext(null)
          }}
          onDelete={() => {
            const key = linkContext.key
            setLinkContext(null)
            void deleteRelationship(key)
          }}
        />
      )}

      {context && byKey[context.element] && (
        <ContextMenu
          target={context}
          element={byKey[context.element]}
          relationships={allRelationships}
          elementsByKey={byKey}
          metamodel={metamodel}
          views={usageViews}
          currentView={viewKey}
          selectedCount={selected.length}
          c4Levels={metamodel.c4Levels}
          onC4View={(level) => {
            setContext(null)
            void createC4View(context.element, level)
          }}
          onArrange={(how) => {
            arrange(how)
            setContext(null)
          }}
          onClose={() => setContext(null)}
          onCopy={() => {
            copySelection()
            setContext(null)
          }}
          onDuplicate={() => {
            duplicateSelection()
            setContext(null)
          }}
          onDrawAgain={() => {
            drawAgain(context.node)
            setContext(null)
          }}
          onRestack={(to) => {
            restackTo([context.node], to)
            setContext(null)
          }}
          // The box, not the element: where an element is drawn twice, this takes away the copy
          // that was right-clicked and leaves the other (ADR-0014).
          onRemoveFromView={() => removeFromView([context.node])}
          onDeleteFromModel={() => deleteFromModel([context.element])}
        />
      )}
    </div>
  )
}

/** Geometry as the layout endpoint wants it. Missing sizes fall back to the default box. */
/**
 * Only the lines that carry something of their own: bendpoints, or ends the architect chose.
 *
 * A line whose ends are the only boxes there are needs no row, and gets none — which is every line
 * on a view where nothing is drawn twice.
 */
function layoutLinks(
  lines: Record<string, { key: string; source: string | null; target: string | null }>,
  vertices: Record<string, Position[]>,
) {
  // How many lines draw each relationship: a second line is worth a row even when it is straight
  // and its ends follow the defaults, because its existence is the information.
  const perKey = new Map<string, number>()
  for (const line of Object.values(lines)) {
    perKey.set(line.key, (perKey.get(line.key) ?? 0) + 1)
  }
  return Object.entries(lines)
    .filter(
      ([lineId, line]) =>
        (vertices[lineId]?.length ?? 0) > 0 ||
        line.source !== null ||
        line.target !== null ||
        (perKey.get(line.key) ?? 0) > 1,
    )
    .map(([lineId, line]) => ({
      id: lineId,
      key: line.key,
      sourceNode: line.source,
      targetNode: line.target,
      vertices: vertices[lineId] ?? [],
    }))
}

/**
 * The view's boxes, one row per placement.
 *
 * Driven by the placements rather than by the positions: a box the canvas is not currently drawing
 * — because a filter narrows what is shown, never what belongs — still has a place on this view and
 * must not be dropped by the save.
 */
function layoutNodes({ placements, positions, sizes, parents, order }: Geometry) {
  return Object.entries(placements).map(([node, element]) => ({
    id: node,
    element,
    x: positions[node]?.x ?? 0,
    y: positions[node]?.y ?? 0,
    width: sizes[node]?.width ?? NODE_WIDTH,
    height: sizes[node]?.height ?? NODE_HEIGHT,
    parent: parents[node] ?? null,
    z: order[node] ?? null,
  }))
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
