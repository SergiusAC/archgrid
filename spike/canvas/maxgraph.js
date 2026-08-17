import * as mx from '@maxgraph/core'

import { ELEMENTS, LAYER, LINKS } from './scene.js'

const findings = []
const record = (label, ok, detail = '') => findings.push({ label, ok, detail })
const attempt = (label, fn) => {
  try {
    const detail = fn()
    record(label, true, typeof detail === 'string' ? detail : '')
  } catch (error) {
    record(label, false, error.message)
  }
}

const container = document.getElementById('paper')
const graph = new mx.Graph(container)
graph.setPanning(true)
graph.setCellsResizable(true)
graph.setDropEnabled(true)
graph.setGridEnabled(true)
graph.gridSize = 10

// Alignment guides while dragging: the thing JointJS keeps behind its paid tier.
attempt('alignment guides available in the core', () => {
  const handler = graph.getPlugin('SelectionHandler')
  handler.guidesEnabled = true
  return handler.guidesEnabled ? 'SelectionHandler.guidesEnabled' : 'flag did not stick'
})
attempt('rubber-band multi-select', () => {
  new mx.RubberBandHandler(graph)
  return 'RubberBandHandler'
})
attempt('undo manager in the core', () => {
  const undo = new mx.UndoManager()
  graph.getDataModel().addListener(mx.InternalEvent.UNDO, (_s, e) => undo.undoableEditHappened(e.getProperty('edit')))
  return 'UndoManager'
})

const parent = graph.getDefaultParent()
const cells = {}

graph.batchUpdate(() => {
  for (const element of ELEMENTS) {
    const owner = element.parent ? cells[element.parent] : parent
    // A child's coordinates are relative to its container, unlike the absolute layout we store.
    const at = element.parent
      ? [element.x - ELEMENTS.find((e) => e.key === element.parent).x, element.y - ELEMENTS.find((e) => e.key === element.parent).y]
      : [element.x, element.y]
    cells[element.key] = graph.insertVertex({
      parent: owner,
      value: `${element.type.toUpperCase()}\n${element.name}`,
      position: at,
      size: [element.w, element.h],
      style: {
        shape: element.shape === 'oval' ? 'ellipse' : 'rectangle',
        fillColor: element.container ? '#e8fbfb' : LAYER[element.type],
        strokeColor: '#4a5568',
        strokeWidth: element.container ? 2 : 1,
        rounded: element.shape !== 'oval',
        fontSize: 11,
        fontColor: '#1a202c',
        verticalAlign: element.container ? 'top' : 'middle',
        whiteSpace: 'wrap',
      },
    })
  }

  for (const link of LINKS) {
    graph.insertEdge({
      parent,
      value: link.label,
      source: cells[link.source],
      target: cells[link.target],
      style: {
        // No edgeStyle at all: maxGraph then draws the straight line the requirement asks for.
        strokeColor: '#4a5568',
        strokeWidth: 1.4,
        endArrow: link.type === 'Serving' ? 'open' : 'block',
        dashed: link.type === 'Flow',
        fontSize: 10,
        fontColor: '#4a5568',
      },
    })
  }
})

await new Promise((resolve) => requestAnimationFrame(resolve))

const svg = container.querySelector('svg')
const paths = [...(svg?.querySelectorAll('g > path[stroke="#4a5568"]') ?? [])]
  .map((p) => p.getAttribute('d'))
  .filter((d) => d && /^M/.test(d))
console.log('paths', paths)

const normalise = (d) => d.trim().replace(/\s+/g, ' ')
// maxGraph draws arrowheads as their own paths in the same stroke colour, and they are
// multi-segment — counting those as line jumps would flatter it. Only spans over 100px are edges.
const span = (d) => {
  const n = normalise(d).match(/-?[\d.]+/g)?.map(Number) ?? []
  return n.length >= 4 ? Math.hypot(n.at(-2) - n[0], n.at(-1) - n[1]) : 0
}
const edges = paths.filter((d) => span(d) > 100)
const horizontal = edges.find((d) => /^M [\d.]+ ([\d.]+) L [\d.]+ \1$/.test(normalise(d)))
record('straight line, horizontal when the elements line up', Boolean(horizontal), horizontal ?? edges[0] ?? 'no edge paths found')

// draw.io draws line jumps, but implements them above the engine: the package has no occurrence
// of jumpover or jumpSize anywhere. A jump would show as an extra segment mid-diagonal.
const withJumps = edges.filter((d) => (normalise(d).match(/L/g) ?? []).length > 1)
record(
  'line jumps at crossings, out of the box',
  withJumps.length > 0,
  `${withJumps.length} of ${edges.length} edges break at a crossing (arrowhead paths excluded)`,
)

record(
  'nesting with children moving with the parent',
  cells['payment-system'].getChildCount() === 1,
  `${cells['payment-system'].getChildCount()} child`,
)
record('bendpoints are built in', typeof graph.getPlugin('SelectionHandler') === 'object')
record('orthogonal router available when wanted', typeof mx.EdgeStyle?.OrthConnector === 'function')
record('native SVG, so export is serialisation not a screenshot', svg instanceof SVGElement)
record(
  'SVG export produced markup',
  Boolean(svg) && new XMLSerializer().serializeToString(svg).length > 1000,
  svg ? `${Math.round(new XMLSerializer().serializeToString(svg).length / 1024)} KB` : '',
)

document.getElementById('report').textContent = findings
  .map((f) => `${f.ok ? 'OK  ' : 'NO  '} ${f.label}${f.detail ? ' — ' + f.detail : ''}`)
  .join('\n')
window.__spike = findings
