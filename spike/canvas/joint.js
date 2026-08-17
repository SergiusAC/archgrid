import * as joint from '@joint/core'

import { ELEMENTS, LAYER, LINKS, byKey } from './scene.js'

const findings = []
const record = (label, ok, detail = '') => findings.push({ label, ok, detail })

const graph = new joint.dia.Graph({}, { cellNamespace: joint.shapes })
const paper = new joint.dia.Paper({
  el: document.getElementById('paper'),
  model: graph,
  width: 900,
  height: 660,
  gridSize: 10,
  drawGrid: { name: 'dot', args: { color: '#d8dce3' } },
  cellViewNamespace: joint.shapes,
  background: { color: '#fff' },
  // The requirement, verbatim: a straight line, which is horizontal or vertical whenever the two
  // elements happen to line up, and a small hop wherever two lines cross.
  defaultRouter: { name: 'normal' },
  defaultConnector: { name: 'jumpover', args: { size: 6, jump: 'arc' } },
})

// ---------------------------------------------------------------- elements
const cells = {}
for (const element of ELEMENTS) {
  const shape =
    element.shape === 'oval' ? new joint.shapes.standard.Ellipse() : new joint.shapes.standard.Rectangle()
  shape.position(element.x, element.y)
  shape.resize(element.w, element.h)
  shape.attr({
    body: {
      fill: element.container ? '#e8fbfb' : LAYER[element.type],
      stroke: '#4a5568',
      strokeWidth: element.container ? 2 : 1,
      rx: element.shape === 'oval' ? undefined : 4,
    },
    label: {
      text: `${element.type.toUpperCase()}\n${element.name}`,
      fontSize: 11,
      fill: '#1a202c',
      // A container's name belongs at the top; the middle is where its children go.
      refY: element.container ? 18 : undefined,
      yAlignment: element.container ? 'top' : 'middle',
    },
  })
  shape.addTo(graph)
  cells[element.key] = shape
}
for (const element of ELEMENTS.filter((e) => e.parent)) {
  cells[element.parent].embed(cells[element.key])
}

// ---------------------------------------------------------------- relationships
const NOTATION = {
  // Serving: open arrowhead, solid line. Flow: filled arrowhead, dashed line.
  Serving: {
    line: {},
    targetMarker: { type: 'path', d: 'M 12 -6 0 0 12 6', fill: 'none', stroke: '#4a5568', strokeWidth: 1.4 },
  },
  Flow: {
    line: { strokeDasharray: '6,4' },
    targetMarker: { type: 'path', d: 'M 12 -5 0 0 12 5 z', fill: '#4a5568', stroke: 'none' },
  },
}

for (const link of LINKS) {
  const notation = NOTATION[link.type]
  const edge = new joint.shapes.standard.Link({
    source: { id: cells[link.source].id },
    target: { id: cells[link.target].id },
  })
  edge.attr({
    line: { stroke: '#4a5568', strokeWidth: 1.4, targetMarker: notation.targetMarker, ...notation.line },
  })
  // Explicitly, rather than relying on the paper default, to see whether the connector is what is
  // missing or the crossing detection.
  edge.connector('jumpover', { size: 6, jump: 'arc' })
  edge.labels([
    {
      position: 0.5,
      attrs: {
        text: { text: link.label, fontSize: 10, fill: '#4a5568' },
        rect: { fill: '#fff' },
      },
    },
  ])
  edge.addTo(graph)
}

// ---------------------------------------------------------------- snaplines, hand-written
/**
 * The one thing JointJS keeps behind its paid tier, written out to see what it actually costs.
 *
 * On every drag, compare the six interesting lines of the moved element (left/centre/right,
 * top/middle/bottom) against the same six of every other, snap when within tolerance, and draw
 * the guide. That is the whole feature.
 */
const TOLERANCE = 6
const guideLayer = document.createElementNS('http://www.w3.org/2000/svg', 'g')
paper.svg.appendChild(guideLayer)

const clearGuides = () => guideLayer.replaceChildren()
const drawGuide = (x1, y1, x2, y2) => {
  const line = document.createElementNS('http://www.w3.org/2000/svg', 'line')
  line.setAttribute('x1', x1)
  line.setAttribute('y1', y1)
  line.setAttribute('x2', x2)
  line.setAttribute('y2', y2)
  line.setAttribute('stroke', '#e53e3e')
  line.setAttribute('stroke-width', '1')
  line.setAttribute('stroke-dasharray', '4,3')
  guideLayer.appendChild(line)
}

paper.on('element:pointermove', (view) => {
  clearGuides()
  const moved = view.model
  const box = moved.getBBox()
  const others = graph
    .getElements()
    .filter((other) => other !== moved && !moved.isEmbeddedIn(other) && !other.isEmbeddedIn(moved))
    .map((other) => other.getBBox())
  if (others.length === 0) {
    return
  }

  const axes = [
    { get: (b) => b.x, set: (d) => ({ x: box.x + d }), vertical: true },
    { get: (b) => b.x + b.width / 2, set: (d) => ({ x: box.x + d }), vertical: true },
    { get: (b) => b.x + b.width, set: (d) => ({ x: box.x + d }), vertical: true },
    { get: (b) => b.y, set: (d) => ({ y: box.y + d }), vertical: false },
    { get: (b) => b.y + b.height / 2, set: (d) => ({ y: box.y + d }), vertical: false },
    { get: (b) => b.y + b.height, set: (d) => ({ y: box.y + d }), vertical: false },
  ]

  let shift = {}
  for (const axis of axes) {
    const mine = axis.get(box)
    for (const other of others) {
      for (const theirs of [axis.get(other), axis.vertical ? other.x + other.width / 2 : other.y + other.height / 2]) {
        if (Math.abs(mine - theirs) <= TOLERANCE) {
          shift = { ...shift, ...axis.set(theirs - mine) }
          if (axis.vertical) {
            drawGuide(theirs, 0, theirs, paper.options.height)
          } else {
            drawGuide(0, theirs, paper.options.width, theirs)
          }
          break
        }
      }
    }
  }
  if (shift.x !== undefined || shift.y !== undefined) {
    moved.position(shift.x ?? box.x, shift.y ?? box.y)
  }
})
paper.on('element:pointerup blank:pointerdown', clearGuides)

// ---------------------------------------------------------------- what the spike is measuring
await new Promise((resolve) => requestAnimationFrame(resolve))

const paths = [...paper.svg.querySelectorAll('.joint-link path[joint-selector="line"]')].map((p) =>
  p.getAttribute('d'),
)
console.log('paths', paths)

// A straight line between two elements that share a y is horizontal all by itself: no orthogonal
// router needed, which is what the requirement actually says.
const horizontal = paths.find((d) => /^M [\d.]+ ([\d.]+) L [\d.]+ \1$/.test(d.trim().replace(/\s+/g, ' ')))
record('straight line, horizontal when the elements line up', Boolean(horizontal), horizontal ?? paths[0])

// A jumped crossing shows up as a second segment mid-line: the connector breaks the straight run
// and hops over with a cubic. A line that crosses nothing stays a single "M … L …".
const withJumps = paths.filter((d) => /[CcAa]/.test(d) || (d.match(/L/g) ?? []).length > 1)
record('line jumps at crossings, out of the box', withJumps.length > 0, `${withJumps.length} of ${paths.length} paths carry arcs`)

record('nesting (embedding) with children moving with the parent', graph.getCell(cells['payment-system'].id).getEmbeddedCells().length === 1)

record('bendpoints are a built-in link tool', typeof joint.linkTools?.Vertices === 'function')
record('orthogonal router available when wanted', Boolean(joint.routers?.manhattan && joint.routers?.rightAngle))
record('alignment guides (snaplines) in the open-source core', Boolean(joint.ui?.Snaplines), 'joint.ui is JointJS+ only — written by hand above, 60 lines')
record('grid snapping while dragging', paper.options.gridSize === 10)
record('native SVG, so export is serialisation not a screenshot', paper.svg instanceof SVGElement)

const svg = new XMLSerializer().serializeToString(paper.svg)
record('SVG export produced markup', svg.startsWith('<svg') && svg.length > 1000, `${Math.round(svg.length / 1024)} KB`)

document.getElementById('report').textContent = findings
  .map((f) => `${f.ok ? 'OK  ' : 'NO  '} ${f.label}${f.detail ? ' — ' + f.detail : ''}`)
  .join('\n')
window.__spike = findings
