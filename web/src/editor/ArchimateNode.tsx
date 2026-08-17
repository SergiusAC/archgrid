import { Handle, Position as HandlePosition, type NodeProps, type Node } from '@xyflow/react'

import type { Layer } from '../api/types.ts'

export type ArchimateNodeData = {
  /** The only semantic thing the canvas knows: which element this box stands for. */
  elementKey: string
  label: string
  typeLabel: string
  layer: Layer
  specialised: boolean
  /** How this node relates to a connection currently being dragged. */
  connectState: 'idle' | 'source' | 'valid' | 'invalid'
}

export type ArchimateNode = Node<ArchimateNodeData, 'archimate'>

/**
 * One element on the canvas.
 *
 * `data` carries a key and presentation, never the element itself. Putting model state into
 * React Flow node data is the usual way these editors become impossible to extend: the canvas
 * starts owning semantics, and then every feature that is not a canvas feature has to go
 * through it.
 */
export default function ArchimateNodeView({ data, selected }: NodeProps<ArchimateNode>) {
  const classes = [
    'ag-node',
    `ag-layer-${data.layer}`,
    selected ? 'ag-node-selected' : '',
    `ag-connect-${data.connectState}`,
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div className={classes} title={data.typeLabel}>
      <Handle type="target" position={HandlePosition.Left} className="ag-handle" />
      <div className="ag-node-type">
        {data.specialised && <span className="ag-stereotype">«{data.typeLabel}»</span>}
        {!data.specialised && data.typeLabel}
      </div>
      <div className="ag-node-label">{data.label}</div>
      <Handle type="source" position={HandlePosition.Right} className="ag-handle" />
    </div>
  )
}
