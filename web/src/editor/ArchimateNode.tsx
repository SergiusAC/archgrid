import {
  Handle,
  NodeResizer,
  Position as HandlePosition,
  type Node,
  type NodeProps,
} from '@xyflow/react'
import { useEffect, useRef, useState } from 'react'

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
  /** True right after creation, when the name is still the type's and wants replacing. */
  editing: boolean
  /** True when this element contains others, so it is drawn as a container. */
  container: boolean
  onCommitName: (key: string, name: string) => void
  onCancelEdit: () => void
}

export type ArchimateNode = Node<ArchimateNodeData, 'archimate'>

/** Every side is a connection point, so a line can leave and arrive wherever it reads best. */
const SIDES = [
  { id: 'top', position: HandlePosition.Top },
  { id: 'right', position: HandlePosition.Right },
  { id: 'bottom', position: HandlePosition.Bottom },
  { id: 'left', position: HandlePosition.Left },
] as const

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
    data.container ? 'ag-node-container' : '',
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div className={classes} title={data.typeLabel}>
      {/* A container holds other elements, so it needs room; a leaf still has a sensible floor. */}
      <NodeResizer
        isVisible={selected}
        minWidth={data.container ? 260 : 120}
        minHeight={data.container ? 160 : 60}
        lineClassName="ag-resize-line"
        handleClassName="ag-resize-handle"
      />

      {/*
        Four sides rather than two. With ConnectionMode.Loose each of these both starts and
        receives a connection, so the user drags from whichever edge faces where they are going
        instead of hunting for the one dot the editor happened to offer.
      */}
      {SIDES.map((side) => (
        <Handle
          key={side.id}
          id={side.id}
          type="source"
          position={side.position}
          className={`ag-handle ag-handle-${side.id}`}
        />
      ))}

      <div className="ag-node-type">
        {data.specialised && <span className="ag-stereotype">«{data.typeLabel}»</span>}
        {!data.specialised && data.typeLabel}
      </div>
      {data.editing ? (
        <NameEditor
          initial={data.label}
          onCommit={(name) => data.onCommitName(data.elementKey, name)}
          onCancel={data.onCancelEdit}
        />
      ) : (
        <div className="ag-node-label">{data.label}</div>
      )}
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
  initial,
  onCommit,
  onCancel,
}: {
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

  return (
    <input
      ref={input}
      className="ag-node-name-input"
      value={value}
      // React Flow would otherwise start a drag or steal the keystrokes.
      onMouseDown={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
      onChange={(event) => setValue(event.target.value)}
      onKeyDown={(event) => {
        event.stopPropagation()
        if (event.key === 'Enter') {
          event.preventDefault()
          const trimmed = value.trim()
          if (trimmed && trimmed !== initial) {
            onCommit(trimmed)
          } else {
            onCancel()
          }
        }
        if (event.key === 'Escape') {
          onCancel()
        }
      }}
      onBlur={() => {
        const trimmed = value.trim()
        if (trimmed && trimmed !== initial) {
          onCommit(trimmed)
        } else {
          onCancel()
        }
      }}
    />
  )
}
