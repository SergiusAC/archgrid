import * as joint from '@joint/core'
import { useCallback, useEffect, useRef, useState } from 'react'

import type { ConnectionRouter, Layer } from '../api/types.ts'
import { GRID, NEST_PADDING } from './layout.ts'
import { notationFor } from './notation.ts'
import { bodyShape, iconFor, iconPlacement } from './shapes.ts'
import type { Position } from './store.ts'

export interface CanvasElement {
  key: string
  name: string
  typeLabel: string
  layer: Layer
  specialised: boolean
  /** The ArchiMate concept, which decides the body shape and the corner glyph. */
  concept: string
  /** The container, only when it is on this view too. */
  parent: string | null
  x: number
  y: number
  width: number
  height: number
}

export interface CanvasLink {
  key: string
  source: string
  target: string
  label: string
  type: string
  /** Implied by a chain through hidden elements, so drawn faintly and named as such. */
  derived: boolean
}

export interface Geometry {
  positions: Record<string, Position>
  sizes: Record<string, { width: number; height: number }>
  /** What each element is drawn inside on this view. Presentation, per view (ADR-0011). */
  parents: Record<string, string | null>
}

interface CanvasProps {
  elements: CanvasElement[]
  links: CanvasLink[]
  selected: string[]
  /** The element whose name is being typed in place, straight after creation. */
  editingKey: string | null
  onSelect: (keys: string[]) => void
  /** The relationship the user has picked, so it can be inspected or deleted. */
  onSelectLink: (key: string | null) => void
  selectedLink: string | null
  /** Fired continuously while dragging or resizing; the caller debounces the save. */
  onGeometry: (geometry: Geometry) => void
  onGeometryStart: () => void
  onGeometryEnd: (geometry: Geometry) => void
  /**
   * Containment is a model change and goes through a command, unlike the coordinates with it.
   *
   * The geometry travels with the event because the command refetches the view, and a debounced
   * layout save still in flight would be answered with what the server held before the drop.
   */
  onNest: (key: string, parent: string | null, geometry: Geometry) => void
  /**
   * Connecting is a tool, not a handle on the element.
   *
   * Archi has no dots on the edges of a box: you pick the connector from the palette, click the
   * source and click the target. Handles put the mechanism of drawing lines on every element,
   * whether or not anyone is drawing one, and made a hover over a box look like an invitation to
   * do something other than read it.
   */
  connecting: boolean
  /** The element clicked first while the connector tool is active. */
  connectFrom: string | null
  onConnectPick: (key: string) => void
  /**
   * The connector tool clicked on empty canvas. The gesture says "from here, something follows" —
   * the editor answers with what may follow, and creates the element and the relationship.
   */
  onMagic: (source: string, clientX: number, clientY: number, at: Position) => void
  onContextMenu: (key: string, clientX: number, clientY: number) => void
  onBlankClick: () => void
  onCommitName: (key: string, name: string) => void
  onCancelEdit: () => void
  /**
   * How this view routes its connections. Straight lines take bendpoints; orthogonal runs do not,
   * which is Archi's rule and a sound one — otherwise the user is fighting the algorithm.
   */
  router: ConnectionRouter
  /** Changing this refits the content; the caller changes it when a view opens. */
  fitToken: string | null
  /**
   * Bumped when geometry changed for a reason the canvas does not know about — auto-layout, or
   * undoing a move. While it stays put, the canvas owns the coordinates of the elements it is
   * already showing: pushing props back into the graph on every frame meant the reconcile raced
   * the drag with values one render out of date, and the element snapped home under the cursor.
   */
  geometryVersion: number
}

const MIN_WIDTH = 120
const MIN_HEIGHT = 60

/**
 * How near counts as aligned, in model units.
 *
 * Wider than the grid step on purpose. Dragging snaps to the grid first, so an element can only
 * land on multiples of it — with a tolerance below the step, a near-miss is always exactly one
 * step away and the guide never fires. The guide has to be able to reach across it.
 */
const GUIDE_TOLERANCE = 12

/** The six lines of a box worth lining up with, and how to read each one. */
const GUIDE_AXES = [
  { vertical: true, of: (b: joint.g.Rect) => b.x, centre: (b: joint.g.Rect) => b.x + b.width / 2 },
  { vertical: true, of: (b: joint.g.Rect) => b.x + b.width / 2, centre: (b: joint.g.Rect) => b.x + b.width / 2 },
  { vertical: true, of: (b: joint.g.Rect) => b.x + b.width, centre: (b: joint.g.Rect) => b.x + b.width / 2 },
  { vertical: false, of: (b: joint.g.Rect) => b.y, centre: (b: joint.g.Rect) => b.y + b.height / 2 },
  { vertical: false, of: (b: joint.g.Rect) => b.y + b.height / 2, centre: (b: joint.g.Rect) => b.y + b.height / 2 },
  { vertical: false, of: (b: joint.g.Rect) => b.y + b.height, centre: (b: joint.g.Rect) => b.y + b.height / 2 },
] as const

/**
 * The strip at the top of a container that its own name occupies, so children start below it.
 *
 * Wide enough to clear the name, which is drawn from y=38 at 12px: at 46 the first child sat on
 * top of the container's own label, and a system whose name is hidden by its own contents is a
 * worse diagram than one that never nested at all.
 */
const CONTAINER_INSET = { top: 60, left: NEST_PADDING, right: NEST_PADDING, bottom: NEST_PADDING }

/**
 * An ArchiMate box: the type in small caps at the top, the name below it, both inside the shape.
 *
 * Two text nodes rather than one label with a newline in it. They are different things and are
 * placed by different rules — the type always at the top, the name centred in a leaf and
 * top-aligned in a container, whose middle belongs to its children. Geometry is written as
 * calc() expressions so the text follows a resize without a listener.
 */
const ArchimateShape = joint.dia.Element.define(
  'archgrid.Element',
  {
    attrs: {
      body: {
        width: 'calc(w)',
        height: 'calc(h)',
        x: 0,
        y: 0,
        rx: 4,
        ry: 4,
        class: 'ag-body',
      },
      type: {
        x: 'calc(0.5*w)',
        y: 14,
        textAnchor: 'middle',
        textVerticalAnchor: 'top',
        fontSize: 8,
        letterSpacing: 0.3,
        class: 'ag-node-type',
      },
      name: {
        x: 'calc(0.5*w)',
        textAnchor: 'middle',
        fontSize: 11,
        fontWeight: 400,
        class: 'ag-node-label',
      },
      icon: { class: 'ag-node-icon' },
    },
  },
  {
    markup: [
      { tagName: 'rect', selector: 'body' },
      { tagName: 'text', selector: 'type' },
      { tagName: 'text', selector: 'name' },
      { tagName: 'path', selector: 'icon' },
    ],
  },
)

/**
 * Settles a newly nested child inside its container, disturbing the drawing as little as possible.
 *
 * Deliberately not `fitEmbeds`, which resizes the container to its contents exactly — that also
 * *shrinks* it, and a container the architect had sized and placed on purpose would snap to a
 * different shape the moment anything was dropped into it. The whole diagram's spacing went with
 * it.
 *
 * So: the container never moves and never shrinks. The child is nudged inside the frame if it
 * landed across an edge, and only then does the container grow, and only far enough.
 */
function tuckIn(child: joint.dia.Element, parent: joint.dia.Element) {
  const frame = parent.getBBox()
  const box = child.getBBox()
  const x = Math.max(box.x, frame.x + CONTAINER_INSET.left)
  const y = Math.max(box.y, frame.y + CONTAINER_INSET.top)
  if (x !== box.x || y !== box.y) {
    child.position(x, y)
  }
  const width = Math.max(frame.width, x - frame.x + box.width + CONTAINER_INSET.right)
  const height = Math.max(frame.height, y - frame.y + box.height + CONTAINER_INSET.bottom)
  if (width !== frame.width || height !== frame.height) {
    parent.resize(width, height)
  }
}

/**
 * The eight grips of a selection box, snapping to the same grid a drag does.
 *
 * One grip in the bottom-right corner meant every resize also moved the element's other two
 * edges out of alignment — to make a box wider on the left you had to widen it on the right and
 * then drag it back. Archi offers all eight, and resizing steps by the same amount as moving, so
 * a diagram that was aligned stays aligned.
 */
const GRIPS = [
  { id: 'top-left', x: 0, y: 0, cursor: 'nwse-resize' },
  { id: 'top', x: 0.5, y: 0, cursor: 'ns-resize' },
  { id: 'top-right', x: 1, y: 0, cursor: 'nesw-resize' },
  { id: 'right', x: 1, y: 0.5, cursor: 'ew-resize' },
  { id: 'bottom-right', x: 1, y: 1, cursor: 'nwse-resize' },
  { id: 'bottom', x: 0.5, y: 1, cursor: 'ns-resize' },
  { id: 'bottom-left', x: 0, y: 1, cursor: 'nesw-resize' },
  { id: 'left', x: 0, y: 0.5, cursor: 'ew-resize' },
] as const

const toGrid = (value: number) => Math.round(value / GRID) * GRID

/**
 * Builds the tool for one grip.
 *
 * `resize` takes a direction, and JointJS then holds the opposite corner still — which is what
 * makes dragging the left edge widen the box leftwards instead of moving it.
 */
function gripTool(grip: (typeof GRIPS)[number]) {
  return joint.elementTools.Control.extend({
    children: [
      {
        tagName: 'rect',
        selector: 'handle',
        attributes: {
          width: 8,
          height: 8,
          x: -4,
          y: -4,
          class: 'ag-resize-handle',
          cursor: grip.cursor,
          'data-grip': grip.id,
        },
      },
    ],
    getPosition(view: joint.dia.ElementView) {
      const size = view.model.size()
      return { x: size.width * grip.x, y: size.height * grip.y }
    },
    setPosition(view: joint.dia.ElementView, coordinates: { x: number; y: number }) {
      const { width, height } = view.model.size()
      // Each grip moves only the edges it touches; the rest of the box is left alone.
      const next = {
        width: grip.x === 1 ? toGrid(coordinates.x) : grip.x === 0 ? width - toGrid(coordinates.x) : width,
        height: grip.y === 1 ? toGrid(coordinates.y) : grip.y === 0 ? height - toGrid(coordinates.y) : height,
      }
      view.model.resize(Math.max(MIN_WIDTH, next.width), Math.max(MIN_HEIGHT, next.height), {
        direction: grip.id,
      })
    },
  }) as unknown as new (options?: object) => joint.dia.ToolView
}

const GRIP_TOOLS = GRIPS.map(gripTool)

/**
 * The canvas, and the only place that knows there is a diagramming library at all (ADR-0010).
 *
 * Everything above it speaks elements, relationships and geometry. That boundary is what made
 * replacing React Flow a week rather than a rewrite, and it is worth defending: nothing about
 * commands, validation or the metamodel belongs below this line.
 */
export default function Canvas(props: CanvasProps) {
  const host = useRef<HTMLDivElement>(null)
  const minimapHost = useRef<HTMLDivElement>(null)
  const graph = useRef<joint.dia.Graph | null>(null)
  const paper = useRef<joint.dia.Paper | null>(null)
  const minimap = useRef<joint.dia.Paper | null>(null)
  // Props change on every render; the handlers live inside JointJS and are wired once.
  const latest = useRef(props)
  latest.current = props
  // True while the user is dragging, so the reconcile effect leaves the graph alone.
  const interacting = useRef(false)
  // True while props are being written into the graph, so our own writes are not reported back
  // as if the user had made them — that loop would commit a containment nobody asked for.
  const applying = useRef(false)
  // The geometryVersion last written into the graph, so a rerender for any other reason does not
  // overwrite where the user has just put things.
  const appliedGeometry = useRef(-1)
  // Which element currently carries the resize grip, so it is not rebuilt on every render.
  const toolsOn = useRef<string | null>(null)
  const [ready, setReady] = useState(false)
  const [editorBox, setEditorBox] = useState<{ x: number; y: number; width: number } | null>(null)

  const geometry = useCallback((): Geometry => {
    const positions: Record<string, Position> = {}
    const sizes: Record<string, { width: number; height: number }> = {}
    const parents: Record<string, string | null> = {}
    for (const element of graph.current?.getElements() ?? []) {
      const key = String(element.id)
      parents[key] = element.getParentCell() ? String(element.getParentCell()!.id) : null
      // Absolute, including embedded children: JointJS positions are absolute, so the stored
      // layout needs no conversion and re-parenting never silently moves anything.
      positions[key] = element.position()
      sizes[key] = element.size()
    }
    return { positions, sizes, parents }
  }, [])

  // ------------------------------------------------------------------ mount
  useEffect(() => {
    const element = host.current
    if (!element) {
      return
    }
    const model = new joint.dia.Graph({}, { cellNamespace: joint.shapes })
    // Deliberately not `el: element`. JointJS views are Backbone views, and `remove()` deletes
    // the element it was given — which under React's double-invoked effects is React's own div.
    // The second mount then rendered into a node no longer in the document, and the canvas was
    // simply blank with nothing in the console to say why.
    const view = new joint.dia.Paper({
      model,
      width: element.clientWidth,
      height: element.clientHeight,
      gridSize: GRID,
      drawGrid: { name: 'dot' },
      background: { color: 'transparent' },
      cellViewNamespace: joint.shapes,
      // The requirement, verbatim: a straight line, horizontal or vertical wherever the elements
      // line up, and a small hop wherever two lines cross.
      defaultRouter: { name: 'normal' },
      defaultConnector: { name: 'jumpover', args: { size: 6, jump: 'arc' } },
      /*
       * Archi calls this "orthogonal connection anchors", and it is what makes a diagram look
       * drafted rather than assembled: the line leaves from the middle of the side facing where it
       * is going. Two elements that line up are then joined by an exactly horizontal or vertical
       * line without anyone arranging it, and two that do not are joined side-to-side rather than
       * corner-to-corner.
       *
       * The help does not describe how Archi computes the anchor, so this is our own reading of
       * the requirement rather than a port of a documented algorithm.
       */
      defaultAnchor: { name: 'midSide', args: { padding: 0 } },
      embeddingMode: true,
      findParentBy: 'bbox',
      frontParentOnly: false,
      // A boolean, not an object: passing `{ linkMove: false }` disabled element dragging
      // altogether — the elements still selected and still opened their menu, so the canvas
      // looked alive while nothing could be moved.
      interactive: true,
      validateEmbedding: (childView, parentView) =>
        // Never into your own descendant, which would detach the subtree from the diagram.
        !parentView.model.isEmbeddedIn(childView.model),
    })
    element.appendChild(view.el)
    graph.current = model
    paper.current = view

    // -------------------------------------------------------------- selection
    view.on('element:pointerclick', (elementView: joint.dia.ElementView, event: joint.dia.Event) => {
      latest.current.onSelectLink(null)
      const key = String(elementView.model.id)
      // With the connector tool active a click means "this end of the line", not "select this".
      if (latest.current.connecting) {
        latest.current.onConnectPick(key)
        return
      }
      const additive = event.shiftKey || event.ctrlKey || event.metaKey
      const current = latest.current.selected
      latest.current.onSelect(
        additive ? (current.includes(key) ? current.filter((k) => k !== key) : [...current, key]) : [key],
      )
    })
    view.on('blank:pointerclick', (event: joint.dia.Event, x: number, y: number) => {
      // A source picked and then empty canvas clicked: that is the Magic Connector asking for a
      // new element at that spot, rather than a plain deselect.
      if (latest.current.connecting && latest.current.connectFrom) {
        latest.current.onMagic(latest.current.connectFrom, event.clientX ?? 0, event.clientY ?? 0, {
          x: Math.round(x),
          y: Math.round(y),
        })
        return
      }
      latest.current.onSelect([])
      latest.current.onSelectLink(null)
      latest.current.onBlankClick()
    })
    // A line is a thing in the model too. Until it could be selected there was no way to delete
    // one at all, which left the only way to unsay something being to delete an element.
    view.on('link:pointerclick', (linkView: joint.dia.LinkView) => {
      latest.current.onSelect([])
      latest.current.onSelectLink(String(linkView.model.id))
    })
    view.on('element:contextmenu', (elementView: joint.dia.ElementView, event: joint.dia.Event) => {
      event.preventDefault()
      latest.current.onSelect([String(elementView.model.id)])
      latest.current.onContextMenu(String(elementView.model.id), event.clientX ?? 0, event.clientY ?? 0)
    })

    // -------------------------------------------------------------- geometry
    view.on('element:pointerdown', () => {
      interacting.current = true
      latest.current.onGeometryStart()
    })
    // Any geometry change is reported, not only one made mid-drag: resizing happens through a
    // tool handle, which never fires the element's own pointer events, so a guard on "is the
    // user dragging" silently dropped every resize on the floor.
    model.on('change:position change:size', () => {
      if (!applying.current) {
        latest.current.onGeometry(geometry())
      }
    })

    /**
     * Containment, the one gesture that is both a model change and a layout change.
     *
     * Listening to the graph rather than to the drop: JointJS assigns the parent inside its own
     * pointerup handling, and reading `getParentCell()` from our own pointerup handler raced it
     * and saw the parent the element had before the drop.
     */
    model.on('change:parent', (cell: joint.dia.Cell) => {
      if (applying.current || !cell.isElement()) {
        return
      }
      const parent = cell.getParentCell()
      if (parent) {
        tuckIn(cell as joint.dia.Element, parent as joint.dia.Element)
      }
      latest.current.onNest(String(cell.id), parent ? String(parent.id) : null, geometry())
    })

    view.on('element:pointerup', () => {
      interacting.current = false
      latest.current.onGeometryEnd(geometry())
    })

    // -------------------------------------------------------------- connecting

    // -------------------------------------------------------------- alignment guides
    /*
     * The thing JointJS keeps behind its paid tier, and the thing that makes a diagram look
     * drafted rather than dropped. On every drag, compare the six interesting lines of the moved
     * element — left, centre, right, top, middle, bottom — against the same six of every other,
     * snap when within tolerance, and show the line that was matched.
     *
     * Drawn into the front layer rather than into the SVG root, because that layer carries the
     * pan and zoom transform: a guide drawn outside it stays put while the diagram moves under it.
     */
    const guides = document.createElementNS('http://www.w3.org/2000/svg', 'g')
    guides.setAttribute('class', 'ag-guides')
    ;(element.querySelector('.joint-front-layer') ?? view.svg).appendChild(guides)
    const clearGuides = () => guides.replaceChildren()

    view.on('element:pointermove', (elementView: joint.dia.ElementView) => {
      clearGuides()
      const moved = elementView.model
      const box = moved.getBBox()
      /*
       * Its own container counts; its own children do not.
       *
       * Centring a child inside the box that holds it is the commonest alignment there is, and
       * excluding the container meant the one guide the user wanted was the one guide missing —
       * while every unrelated element nearby went on tugging the child off centre. Descendants
       * stay excluded because they travel with the element, so lining up with them is meaningless.
       */
      const others = model
        .getElements()
        .filter((other) => other !== moved && !other.isEmbeddedIn(moved))
        .map((other) => other.getBBox())
      if (others.length === 0) {
        return
      }
      const span = model.getBBox() ?? box

      const shift: { x?: number; y?: number } = {}
      for (const axis of GUIDE_AXES) {
        const mine = axis.of(box)
        // The nearest candidate, not the last one that happened to be in range: iterating and
        // overwriting made the winner depend on the order elements were added to the graph.
        let best: { line: number; distance: number } | null = null
        for (const other of others) {
          for (const theirs of [axis.of(other), axis.centre(other)]) {
            const distance = Math.abs(mine - theirs)
            if (distance <= GUIDE_TOLERANCE && (!best || distance < best.distance)) {
              best = { line: theirs, distance }
            }
          }
        }
        if (!best) {
          continue
        }
        if (axis.vertical) {
          shift.x = box.x + (best.line - mine)
        } else {
          shift.y = box.y + (best.line - mine)
        }
        const line = document.createElementNS('http://www.w3.org/2000/svg', 'line')
        const from = axis.vertical ? span.y - 200 : span.x - 200
        const to = axis.vertical ? span.y + span.height + 200 : span.x + span.width + 200
        line.setAttribute('x1', String(axis.vertical ? best.line : from))
        line.setAttribute('y1', String(axis.vertical ? from : best.line))
        line.setAttribute('x2', String(axis.vertical ? best.line : to))
        line.setAttribute('y2', String(axis.vertical ? to : best.line))
        line.setAttribute('class', 'ag-guide')
        guides.appendChild(line)
      }
      if (shift.x !== undefined || shift.y !== undefined) {
        moved.position(shift.x ?? box.x, shift.y ?? box.y)
      }
    })
    view.on('element:pointerup blank:pointerdown', clearGuides)

    // -------------------------------------------------------------- routing a line by hand
    // Bendpoints appear on hover and belong to the line, not to the selection: the architect drew
    // it, so the architect routes it. Wired once — re-registering per render stacked the handlers.
    view.on('link:mouseenter', (linkView: joint.dia.LinkView) => {
      if (latest.current.router === 'orthogonal') {
        return
      }
      linkView.addTools(
        new joint.dia.ToolsView({
          tools: [new joint.linkTools.Vertices(), new joint.linkTools.Segments()],
        }),
      )
    })
    view.on('link:mouseleave', (linkView: joint.dia.LinkView) => linkView.removeTools())

    // -------------------------------------------------------------- pan and zoom
    let panning: { x: number; y: number; tx: number; ty: number } | null = null
    view.on('blank:pointerdown', (event: joint.dia.Event) => {
      const translate = view.translate()
      panning = { x: event.clientX ?? 0, y: event.clientY ?? 0, tx: translate.tx, ty: translate.ty }
    })
    const onPointerMove = (event: PointerEvent) => {
      if (panning) {
        view.translate(panning.tx + event.clientX - panning.x, panning.ty + event.clientY - panning.y)
      }
    }
    const onPointerUp = () => {
      panning = null
    }
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      const scale = view.scale().sx
      const next = Math.min(2.5, Math.max(0.2, scale * (event.deltaY < 0 ? 1.08 : 1 / 1.08)))
      // Zoom about the pointer, not the origin, or the diagram slides away as you zoom into it.
      const box = host.current!.getBoundingClientRect()
      const local = view.clientToLocalPoint({ x: event.clientX, y: event.clientY })
      view.scale(next, next)
      const after = view.localToClientPoint(local)
      const translate = view.translate()
      view.translate(translate.tx + event.clientX - after.x, translate.ty + event.clientY - after.y)
      void box
    }
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', onPointerUp)
    element.addEventListener('wheel', onWheel, { passive: false })

    // -------------------------------------------------------------- naming in place
    view.on('element:pointerdblclick', (elementView: joint.dia.ElementView) => {
      latest.current.onSelect([String(elementView.model.id)])
      latest.current.onCommitName(String(elementView.model.id), '')
    })

    const observer = new ResizeObserver(() => view.setDimensions(element.clientWidth, element.clientHeight))
    observer.observe(element)

    // -------------------------------------------------------------- minimap
    const small = minimapHost.current
    if (small) {
      const overview = new joint.dia.Paper({
        model,
        width: small.clientWidth,
        height: small.clientHeight,
        interactive: false,
        background: { color: 'transparent' },
        cellViewNamespace: joint.shapes,
      })
      small.appendChild(overview.el)
      minimap.current = overview
    }

    setReady(true)
    return () => {
      observer.disconnect()
      window.removeEventListener('pointermove', onPointerMove)
      window.removeEventListener('pointerup', onPointerUp)
      element.removeEventListener('wheel', onWheel)
      minimap.current?.remove()
      view.remove()
      // Explicitly, and not only through the view: React's double-invoked effects left the first
      // paper's SVG in the container, so a second one was drawn beside a stale copy of the same
      // diagram — everything rendered, nothing lined up, and the console said nothing.
      element.replaceChildren()
      small?.replaceChildren()
      graph.current = null
      paper.current = null
      minimap.current = null
    }
  }, [geometry])

  // ------------------------------------------------------------------ elements
  useEffect(() => {
    const model = graph.current
    if (!model || !ready || interacting.current) {
      return
    }
    applying.current = true
    const wanted = new Set(props.elements.map((element) => element.key))
    for (const cell of model.getElements()) {
      if (!wanted.has(String(cell.id))) {
        cell.remove()
      }
    }

    const geometryIsNews = appliedGeometry.current !== props.geometryVersion
    appliedGeometry.current = props.geometryVersion

    for (const element of props.elements) {
      let cell = model.getCell(element.key) as joint.dia.Element | undefined
      const isNew = !cell
      if (!cell) {
        cell = new ArchimateShape({ id: element.key })
        cell.addTo(model)
      }
      const container = props.elements.some((other) => other.parent === element.key)
      if (isNew || geometryIsNews) {
        cell.position(element.x, element.y)
        cell.resize(element.width, element.height)
      }
      cell.attr({
        root: {
          // JointJS's own classes are repeated deliberately: setting `class` replaces the
          // attribute wholesale, and dropping `joint-element` leaves the library looking for
          // views it can no longer find.
          class: [
            'joint-cell',
            'joint-element',
            'joint-theme-default',
            'ag-node',
            `ag-layer-${element.layer}`,
            container ? 'ag-node-container' : '',
            props.selected.includes(element.key) ? 'ag-node-selected' : '',
            props.connectFrom === element.key ? 'ag-node-connect-source' : '',
          ]
            .filter(Boolean)
            .join(' '),
          'data-key': element.key,
        },
        // Behaviour is rounded, a service is a pill, everything else is square — the geometry is
        // half of what the notation says, and a reader takes it in before any word.
        body: (() => {
          // A junction is a dot; everything else is a rectangle, as Archi draws it.
          const radius = bodyShape(element.concept) === 'dot' ? Math.min(element.width, element.height) / 2 : 2
          return { rx: radius, ry: radius }
        })(),
        icon: { d: iconFor(element.concept), transform: iconPlacement(element.width) },
        type: { text: element.specialised ? `«${element.typeLabel}»` : element.typeLabel.toUpperCase() },
        name: {
          text: element.name,
          // Inside, at the top, under its type — for every element and not only for containers.
          // ArchiMate reads top-down: what it is, then what it is called. Centring the name meant
          // two elements of different heights put their names on different lines, and a column of
          // boxes stopped scanning as a column.
          y: 30,
          textVerticalAnchor: 'top',
          // A name is as long as the system is called; wrapping keeps it inside the box rather
          // than across the diagram.
          // A container's name is capped so long names cannot eat the room its children need.
          textWrap: { width: 'calc(w-20)', height: container ? 32 : 'calc(h-38)', ellipsis: true },
        },
      })
    }

    // Embedding after every cell exists, or a child is embedded into a parent not yet added.
    for (const element of props.elements) {
      const cell = model.getCell(element.key) as joint.dia.Element
      const current = cell.getParentCell()
      if (element.parent && String(current?.id ?? '') !== element.parent) {
        ;(model.getCell(element.parent) as joint.dia.Element).embed(cell)
      } else if (!element.parent && current) {
        ;(current as joint.dia.Element).unembed(cell)
      }
    }
    applying.current = false
  }, [props.elements, props.selected, props.connectFrom, props.geometryVersion, ready])

  // ------------------------------------------------------------------ relationships
  useEffect(() => {
    const model = graph.current
    if (!model || !ready) {
      return
    }
    const wanted = new Set(props.links.map((link) => link.key))
    for (const cell of model.getLinks()) {
      if (!wanted.has(String(cell.id))) {
        cell.remove()
      }
    }
    for (const link of props.links) {
      const notation = notationFor(link.type)
      const existing = model.getCell(link.key) as joint.dia.Link | undefined
      const cell =
        existing ??
        new joint.shapes.standard.Link({
          id: link.key,
          source: { id: link.source },
          target: { id: link.target },
        })
      /*
       * A marker is either present or explicitly turned off with SVG's own `none`.
       *
       * Passing null or undefined to `sourceMarker` is not the same thing: JointJS writes the
       * value straight into the attribute, and `marker-start="undefined"` is a reference to
       * nothing. The line still draws, so the diagram merely looks plain — which is exactly the
       * defect this project shipped once already, under a different library.
       */
      const line: Record<string, unknown> = {
        class: 'ag-link-line',
        stroke: 'currentColor',
        strokeWidth: 1.6,
        strokeDasharray: link.derived ? '3 5' : (notation.dash ?? 'none'),
      }
      if (notation.markerStart) {
        line.sourceMarker = notation.markerStart
      } else {
        line['marker-start'] = 'none'
      }
      if (notation.markerEnd) {
        line.targetMarker = notation.markerEnd
      } else {
        line['marker-end'] = 'none'
      }

      // Typed as Selectors up front: dia.Link.attr's first overload takes a path, and an inline
      // object literal binds to that instead of to the selectors overload.
      const attrs = {
        root: {
          class:
            `joint-cell joint-link joint-theme-default ag-link` +
            (link.derived ? ' ag-link-derived' : '') +
            (props.selectedLink === link.key ? ' ag-link-selected' : ''),
          'data-key': link.key,
        },
        line,
      } as joint.dia.Cell.Selectors
      cell.attr(attrs)
      // Per view, not per link: the choice belongs to the diagram, and Archi puts it in the same
      // place. Orthogonal routing drops any bendpoints the line already had, which is why the
      // tools that create them are only offered on the straight router.
      cell.router(props.router === 'orthogonal' ? { name: 'rightAngle' } : { name: 'normal' })
      cell.labels([
        {
          position: 0.5,
          attrs: {
            text: { text: link.label, class: 'ag-link-label' },
            // Two crossing links put their labels in the same place; without a plate underneath
            // the two words print on top of each other and neither is readable.
            rect: {
              ref: 'text',
              class: 'ag-link-label-plate',
              x: 'calc(x-4)',
              y: 'calc(y-2)',
              width: 'calc(w+8)',
              height: 'calc(h+4)',
            },
          },
        },
      ])
      if (!existing) {
        cell.addTo(model)
      }
    }
  }, [props.links, props.router, props.selectedLink, ready])

  // The minimap is a second paper over the same graph, so it needs its own refit whenever the
  // content changes — otherwise it shows the top-left corner at full size, which reads as a
  // minimap that has failed rather than one showing exactly what it was told to.
  useEffect(() => {
    minimap.current?.transformToFitContent({
      padding: 8,
      maxScale: 0.4,
      minScale: 0.02,
      useModelGeometry: true,
      verticalAlign: 'middle',
      horizontalAlign: 'middle',
    })
  }, [props.elements, props.links, ready])

  /**
   * The resize grip, attached to the one selected element and to nothing else.
   *
   * Only when the selection actually changes. Re-adding the tool on every render replaced the
   * grip mid-gesture, and since a resize emits geometry on every frame, the very first frame
   * destroyed the drag: the element grew by a few pixels and then stopped following the pointer.
   */
  useEffect(() => {
    const view = paper.current
    const model = graph.current
    if (!view || !model || !ready) {
      return
    }
    const only = props.selected.length === 1 ? props.selected[0] : null
    if (toolsOn.current === only) {
      return
    }
    if (toolsOn.current) {
      ;(model.getCell(toolsOn.current)?.findView(view) as joint.dia.ElementView | undefined)?.removeTools()
    }
    if (only) {
      const elementView = model.getCell(only)?.findView(view) as joint.dia.ElementView | undefined
      elementView?.addTools(new joint.dia.ToolsView({ tools: GRIP_TOOLS.map((Tool) => new Tool()) }))
    }
    toolsOn.current = only
  }, [props.selected, ready])

  // ------------------------------------------------------------------ fit
  useEffect(() => {
    const view = paper.current
    if (!view || !ready || !props.fitToken || props.elements.length === 0) {
      return
    }
    const id = window.requestAnimationFrame(() =>
      view.transformToFitContent({
        padding: 40,
        maxScale: 1,
        minScale: 0.2,
        useModelGeometry: true,
        verticalAlign: 'middle',
        horizontalAlign: 'middle',
      }),
    )
    return () => window.cancelAnimationFrame(id)
  }, [props.fitToken, props.elements.length, ready])

  // ------------------------------------------------------------------ name editor overlay
  useEffect(() => {
    const view = paper.current
    const model = graph.current
    if (!view || !model || !props.editingKey) {
      setEditorBox(null)
      return
    }
    const cell = model.getCell(props.editingKey) as joint.dia.Element | undefined
    if (!cell) {
      setEditorBox(null)
      return
    }
    const box = cell.getBBox()
    const topLeft = view.localToPaperPoint(box.x, box.y)
    const bottomRight = view.localToPaperPoint(box.x + box.width, box.y + box.height)
    setEditorBox({
      x: topLeft.x,
      y: (topLeft.y + bottomRight.y) / 2 - 14,
      width: bottomRight.x - topLeft.x,
    })
  }, [props.editingKey, props.elements])

  const zoomBy = (factor: number) => {
    const view = paper.current
    if (!view) {
      return
    }
    const scale = Math.min(2.5, Math.max(0.2, view.scale().sx * factor))
    view.scale(scale, scale)
  }

  return (
    <div className={props.connecting ? 'ag-paper-wrap ag-connecting' : 'ag-paper-wrap'}>
      <div className="ag-paper" ref={host} />

      {editorBox && props.editingKey && (
        <NameEditor
          box={editorBox}
          initial={props.elements.find((element) => element.key === props.editingKey)?.name ?? ''}
          onCommit={(name) => props.onCommitName(props.editingKey!, name)}
          onCancel={props.onCancelEdit}
        />
      )}

      <div className="ag-controls">
        <button type="button" onClick={() => zoomBy(1.2)} title="Zoom in">
          +
        </button>
        <button type="button" onClick={() => zoomBy(1 / 1.2)} title="Zoom out">
          −
        </button>
        <button
          type="button"
          onClick={() => paper.current?.transformToFitContent({
        padding: 40,
        maxScale: 1,
        minScale: 0.2,
        useModelGeometry: true,
        verticalAlign: 'middle',
        horizontalAlign: 'middle',
      })}
          title="Fit to content"
        >
          ⤢
        </button>
      </div>

      <div className="ag-minimap" ref={minimapHost} />
    </div>
  )
}

/**
 * Names the element where it sits.
 *
 * A newly created element is named after its type, and sending the user to a side panel to fix
 * that made ten elements cost ten trips across the screen.
 */
function NameEditor({
  box,
  initial,
  onCommit,
  onCancel,
}: {
  box: { x: number; y: number; width: number }
  initial: string
  onCommit: (name: string) => void
  onCancel: () => void
}) {
  const [value, setValue] = useState(initial)
  const input = useRef<HTMLInputElement>(null)

  useEffect(() => {
    input.current?.focus()
    input.current?.select()
  }, [])

  const finish = () => {
    const trimmed = value.trim()
    if (trimmed && trimmed !== initial) {
      onCommit(trimmed)
    } else {
      onCancel()
    }
  }

  return (
    <input
      ref={input}
      className="ag-node-name-input"
      style={{ left: box.x + 10, top: box.y, width: Math.max(60, box.width - 20) }}
      value={value}
      onChange={(event) => setValue(event.target.value)}
      onKeyDown={(event) => {
        event.stopPropagation()
        if (event.key === 'Enter') {
          event.preventDefault()
          finish()
        }
        if (event.key === 'Escape') {
          onCancel()
        }
      }}
      onBlur={finish}
    />
  )
}
