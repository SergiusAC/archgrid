import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  applyNodeChanges,
  type Connection,
  type Edge,
  type NodeChange,
  type ReactFlowInstance,
} from '@xyflow/react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import type { Api } from '../api/client.ts'
import { ApiError } from '../api/client.ts'
import type { Command, ElementType, ModelElement, ModelRelationship } from '../api/types.ts'
import { LAYER_COLORS, Metamodel } from '../metamodel/Metamodel.ts'
import ArchimateNodeView, { type ArchimateNode } from './ArchimateNode.tsx'
import ContextMenu, { type ContextTarget } from './ContextMenu.tsx'
import MarkerDefs from './MarkerDefs.tsx'
import Palette from './Palette.tsx'
import { NODE_HEIGHT, NODE_WIDTH, autoLayout, nextFreePosition } from './layout.ts'
import { marker, notationFor } from './notation.ts'
import { invert, useHistoryStore, useLayoutStore } from './store.ts'

import '@xyflow/react/dist/style.css'

const nodeTypes = { archimate: ArchimateNodeView }

interface EditorProps {
  api: Api
  workspace: string
  model: string
  metamodel: Metamodel
  profiles: { name: string; conceptType: string }[]
}

export default function Editor({ api, workspace, model, metamodel, profiles }: EditorProps) {
  const queryClient = useQueryClient()
  const [viewpoint, setViewpoint] = useState('layered')
  const [relationshipType, setRelationshipType] = useState('Serving')
  const [connectingFrom, setConnectingFrom] = useState<string | null>(null)
  const [message, setMessage] = useState<{ kind: 'error' | 'info'; text: string } | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  const [context, setContext] = useState<ContextTarget | null>(null)
  const flow = useRef<ReactFlowInstance<ArchimateNode, Edge> | null>(null)
  const fitted = useRef(false)

  const positions = useLayoutStore((state) => state.positions)
  const hidden = useLayoutStore((state) => state.hidden)
  const setPositions = useLayoutStore((state) => state.setPositions)
  const setPosition = useLayoutStore((state) => state.setPosition)
  const hide = useLayoutStore((state) => state.hide)
  const show = useLayoutStore((state) => state.show)
  const showAll = useLayoutStore((state) => state.showAll)
  const history = useHistoryStore()

  const snapshot = useQuery({
    queryKey: ['model', workspace, model],
    queryFn: async () => {
      const [meta, elements, relationships] = await Promise.all([
        api.getModel(workspace, model),
        api.elements(workspace, model),
        api.relationships(workspace, model),
      ])
      return { meta, elements, relationships }
    },
  })

  const elements = useMemo(() => snapshot.data?.elements ?? [], [snapshot.data])
  const relationships = useMemo(() => snapshot.data?.relationships ?? [], [snapshot.data])
  const headCommitId = snapshot.data?.meta.headCommitId ?? null

  const byKey = useMemo(
    () => Object.fromEntries(elements.map((element) => [element.key, element])) as Record<string, ModelElement>,
    [elements],
  )
  const relationshipsByKey = useMemo(
    () =>
      Object.fromEntries(relationships.map((relationship) => [relationship.key, relationship])) as Record<
        string,
        ModelRelationship
      >,
    [relationships],
  )

  const visibleElements = useMemo(() => elements.filter((element) => !hidden[element.key]), [elements, hidden])
  const visibleRelationships = useMemo(
    () =>
      relationships.filter(
        (relationship) => !hidden[relationship.source] && !hidden[relationship.target],
      ),
    [relationships, hidden],
  )
  const hiddenElements = useMemo(() => elements.filter((element) => hidden[element.key]), [elements, hidden])

  useEffect(() => {
    const missing = visibleElements.filter((element) => !positions[element.key])
    if (missing.length === 0) {
      return
    }
    if (Object.keys(positions).length === 0) {
      void autoLayout(visibleElements, visibleRelationships).then(setPositions)
    } else {
      const placed: Record<string, { x: number; y: number }> = {}
      let taken = positions
      for (const element of missing) {
        const position = nextFreePosition(taken)
        placed[element.key] = position
        taken = { ...taken, [element.key]: position }
      }
      setPositions(placed)
    }
  }, [visibleElements, visibleRelationships, positions, setPositions])

  // Auto-layout resolves after the canvas has already mounted, so React Flow's own `fitView`
  // has by then fitted a diagram whose nodes were all at the origin. Refit once the first real
  // positions land, otherwise the model opens zoomed into a corner.
  useEffect(() => {
    if (fitted.current || !flow.current || visibleElements.length === 0) {
      return
    }
    if (visibleElements.every((element) => positions[element.key])) {
      fitted.current = true
      window.requestAnimationFrame(() => flow.current?.fitView({ padding: 0.2, duration: 300 }))
    }
  }, [visibleElements, positions])

  const apply = useMutation({
    mutationFn: async ({ commands, label }: { commands: Command[]; label: string }) => {
      const inverse = invert(commands, { elements: byKey, relationships: relationshipsByKey })
      await api.applyCommands(workspace, model, { baseCommitId: headCommitId, message: label, commands })
      return { inverse, commands, label }
    },
    onSuccess: async ({ inverse, commands, label }) => {
      history.push({ forward: commands, inverse, label })
      setMessage(null)
      await queryClient.invalidateQueries({ queryKey: ['model', workspace, model] })
    },
    onError: (error: unknown) => setMessage({ kind: 'error', text: describe(error) }),
  })

  /** Applies a batch without recording it, so undo and redo do not stack on themselves. */
  const applySilently = useCallback(
    async (commands: Command[], label: string) => {
      try {
        await api.applyCommands(workspace, model, { baseCommitId: headCommitId, message: label, commands })
        setMessage(null)
        await queryClient.invalidateQueries({ queryKey: ['model', workspace, model] })
        return true
      } catch (error) {
        setMessage({ kind: 'error', text: describe(error) })
        return false
      }
    },
    [api, workspace, model, headCommitId, queryClient],
  )

  const nodes: ArchimateNode[] = useMemo(
    () =>
      visibleElements.map((element) => {
        const type = metamodel.elementType(element.conceptType)
        return {
          id: element.key,
          type: 'archimate' as const,
          position: positions[element.key] ?? { x: 0, y: 0 },
          // Explicit dimensions: the minimap draws from these, and without them it renders
          // an empty rectangle until every node has been measured.
          width: NODE_WIDTH,
          height: NODE_HEIGHT,
          selected: selected.includes(element.key),
          data: {
            elementKey: element.key,
            label: element.name,
            typeLabel: element.profile ?? type?.name ?? element.conceptType,
            layer: type?.layer ?? 'other',
            specialised: element.profile != null,
            connectState: connectionState(element, connectingFrom, byKey, metamodel, relationshipType),
          },
        }
      }),
    [visibleElements, positions, selected, metamodel, connectingFrom, byKey, relationshipType],
  )

  const edges: Edge[] = useMemo(
    () =>
      visibleRelationships.map((relationship) => {
        const notation = notationFor(relationship.type)
        return {
          id: relationship.key,
          source: relationship.source,
          target: relationship.target,
          label: relationship.name ?? relationship.type,
          markerEnd: marker(notation.markerEnd),
          markerStart: marker(notation.markerStart),
          animated: notation.animated ?? false,
          style: notation.dash ? { strokeDasharray: notation.dash } : undefined,
          className: `ag-edge ag-edge-${relationship.type.toLowerCase()}`,
        }
      }),
    [visibleRelationships],
  )

  const onNodesChange = useCallback(
    (changes: NodeChange<ArchimateNode>[]) => {
      // Position changes never reach the server: dragging a box is not a model change (ADR-0003).
      const moved = applyNodeChanges(changes, nodes)
      for (const change of changes) {
        if (change.type === 'position' && change.position) {
          setPosition(change.id, change.position)
        }
        if (change.type === 'select') {
          setSelected(moved.filter((node) => node.selected).map((node) => node.id))
        }
      }
    },
    [nodes, setPosition],
  )

  const isValidConnection = useCallback(
    (connection: Connection | Edge) => {
      const source = byKey[connection.source]
      const target = byKey[connection.target]
      if (!source || !target) {
        return false
      }
      return metamodel.permits(source.conceptType, target.conceptType, relationshipType)
    },
    [byKey, metamodel, relationshipType],
  )

  const onConnect = useCallback(
    (connection: Connection) => {
      const source = byKey[connection.source]
      const target = byKey[connection.target]
      if (!source || !target) {
        return
      }
      if (!metamodel.permits(source.conceptType, target.conceptType, relationshipType)) {
        setMessage({
          kind: 'error',
          text: metamodel.explain(source.conceptType, target.conceptType, relationshipType),
        })
        return
      }
      const key = uniqueKey(`${source.key}-${relationshipType.toLowerCase()}-${target.key}`, relationshipsByKey)
      apply.mutate({
        commands: [
          { op: 'add-relationship', key, type: relationshipType, source: source.key, target: target.key },
        ],
        label: `Connect ${source.name} to ${target.name}`,
      })
    },
    [byKey, metamodel, relationshipType, relationshipsByKey, apply],
  )

  const createElement = useCallback(
    (typeRef: string, displayName: string) => {
      const key = uniqueKey(displayName, byKey)
      apply.mutate({
        commands: [{ op: 'create-element', key, type: typeRef, name: displayName }],
        label: `Add ${displayName}`,
      })
    },
    [byKey, apply],
  )

  /**
   * Takes elements off this diagram. Not a model change, so no command and no commit — the
   * element keeps existing, keeps its relationships, and can be put back from the panel.
   */
  const removeFromView = useCallback(
    (keys: string[]) => {
      if (keys.length === 0) {
        return
      }
      hide(keys)
      setSelected((current) => current.filter((key) => !keys.includes(key)))
      setContext(null)
      const names = keys.map((key) => byKey[key]?.name ?? key).join(', ')
      setMessage({ kind: 'info', text: `${names} removed from this view. Still in the model.` })
    },
    [hide, byKey],
  )

  /** Deletes from the model itself: permanent, everywhere, and it takes the relationships with it. */
  const deleteFromModel = useCallback(
    (keys: string[]) => {
      if (keys.length === 0) {
        return
      }
      const affected = relationships.filter(
        (relationship) => keys.includes(relationship.source) || keys.includes(relationship.target),
      )
      const names = keys.map((key) => byKey[key]?.name ?? key).join(', ')
      const confirmed = window.confirm(
        `Delete ${names} from the model?\n\n` +
          (affected.length > 0
            ? `${affected.length} relationship(s) will be deleted with it:\n` +
              affected
                .slice(0, 8)
                .map((relationship) => `  • ${relationship.type}: ${relationship.source} → ${relationship.target}`)
                .join('\n') +
              (affected.length > 8 ? `\n  …and ${affected.length - 8} more` : '') +
              '\n\n'
            : 'Nothing else references it.\n\n') +
          'This removes it from the model everywhere, not just from this view.',
      )
      setContext(null)
      if (confirmed) {
        apply.mutate({
          commands: keys.map((key) => ({ op: 'delete-element', key })),
          label: `Delete ${names}`,
        })
      }
    },
    [relationships, byKey, apply],
  )

  const undo = useCallback(async () => {
    const entry = history.popUndo()
    if (!entry) {
      return
    }
    if (await applySilently(entry.inverse, `Undo: ${entry.label}`)) {
      history.pushRedo(entry)
    }
  }, [history, applySilently])

  const redo = useCallback(async () => {
    const entry = history.popRedo()
    if (!entry) {
      return
    }
    if (await applySilently(entry.forward, `Redo: ${entry.label}`)) {
      history.push(entry)
    }
  }, [history, applySilently])

  // Ctrl/Cmd+Z, Ctrl+Shift+Z, Ctrl+Y, and Delete for taking things off the diagram. Delete is
  // deliberately the non-destructive one: the key that is easiest to hit by accident must not
  // be the one that changes the model.
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      if (
        target &&
        (target.isContentEditable ||
          ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
      ) {
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
        event.preventDefault()
        removeFromView(selected)
      } else if (event.key === 'Escape') {
        setContext(null)
      }
    }

    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [undo, redo, selected, removeFromView])

  const relayout = useCallback(() => {
    void autoLayout(visibleElements, visibleRelationships).then((laid) => {
      setPositions(laid)
      window.requestAnimationFrame(() => flow.current?.fitView({ padding: 0.2, duration: 300 }))
    })
  }, [visibleElements, visibleRelationships, setPositions])

  if (snapshot.isPending) {
    return <div className="ag-editor-loading">Loading model…</div>
  }
  if (snapshot.isError) {
    return <div className="ag-editor-loading error">{describe(snapshot.error)}</div>
  }

  return (
    <div className="ag-editor">
      <MarkerDefs />

      <div className="ag-sidebar">
        <Palette
          metamodel={metamodel}
          viewpoint={viewpoint}
          onViewpointChange={setViewpoint}
          onCreate={(type: ElementType) => createElement(type.concept, type.name)}
          profiles={profiles}
          onCreateProfiled={(name) => createElement(name, name)}
          busy={apply.isPending}
        />

        {hiddenElements.length > 0 && (
          <section className="ag-hidden-panel">
            <h3>
              Not on this view ({hiddenElements.length})
              <button type="button" className="ag-link" onClick={showAll}>
                show all
              </button>
            </h3>
            <ul>
              {hiddenElements.map((element) => (
                <li key={element.key}>
                  <button type="button" className="ag-link" onClick={() => show(element.key)}>
                    {element.name}
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>

      <div className="ag-canvas-area">
        <div className="ag-toolbar">
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
          <button type="button" onClick={relayout}>
            Auto-layout
          </button>
          <button
            type="button"
            onClick={() => void undo()}
            disabled={history.undoStack.length === 0}
            title="Ctrl+Z"
          >
            Undo
          </button>
          <button
            type="button"
            onClick={() => void redo()}
            disabled={history.redoStack.length === 0}
            title="Ctrl+Shift+Z or Ctrl+Y"
          >
            Redo
          </button>
          <button
            type="button"
            onClick={() => removeFromView(selected)}
            disabled={selected.length === 0}
            title="Delete — takes it off this diagram only"
          >
            Remove from view
          </button>
          <button
            type="button"
            className="ag-danger"
            onClick={() => deleteFromModel(selected)}
            disabled={selected.length === 0}
            title="Permanent, everywhere"
          >
            Delete from model
          </button>
          <span className="ag-toolbar-spacer" />
          <span className="ag-muted">
            {visibleElements.length} of {elements.length} elements · {visibleRelationships.length} relationships
          </span>
        </div>

        {message && <div className={`ag-banner ag-banner-${message.kind}`}>{message.text}</div>}

        <div className="ag-canvas">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onConnect={onConnect}
            isValidConnection={isValidConnection}
            onConnectStart={(_event, params) => setConnectingFrom(params.nodeId ?? null)}
            onConnectEnd={() => setConnectingFrom(null)}
            onNodeContextMenu={(event, node) => {
              event.preventDefault()
              setContext({ elementKey: node.id, x: event.clientX, y: event.clientY })
            }}
            onPaneClick={() => setContext(null)}
            // React Flow's own delete would remove nodes from the canvas without going through
            // a command, so the diagram would disagree with the model until the next reload.
            deleteKeyCode={null}
            onInit={(instance) => {
              flow.current = instance
            }}
            fitView
          >
            <Background gap={16} />
            <Controls />
            <MiniMap
              pannable
              zoomable
              nodeColor={(node) => LAYER_COLORS[(node.data as { layer?: keyof typeof LAYER_COLORS }).layer ?? 'other']}
              nodeStrokeColor="#6b7280"
              nodeStrokeWidth={2}
            />
          </ReactFlow>
        </div>
      </div>

      {context && byKey[context.elementKey] && (
        <ContextMenu
          target={context}
          element={byKey[context.elementKey]}
          relationships={relationships}
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

/**
 * How a node should look while a connection is being dragged out of another one.
 *
 * This is the feature that makes the product something other than a drawing tool: the
 * metamodel answers "may this exist" before the user lets go of the mouse.
 */
function connectionState(
  element: ModelElement,
  connectingFrom: string | null,
  byKey: Record<string, ModelElement>,
  metamodel: Metamodel,
  relationshipType: string,
): 'idle' | 'source' | 'valid' | 'invalid' {
  if (!connectingFrom) {
    return 'idle'
  }
  if (connectingFrom === element.key) {
    return 'source'
  }
  const source = byKey[connectingFrom]
  if (!source) {
    return 'idle'
  }
  return metamodel.permits(source.conceptType, element.conceptType, relationshipType) ? 'valid' : 'invalid'
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
      return 'Someone else changed this model. Reload to get their changes before continuing.'
    }
    return error.message
  }
  return error instanceof Error ? error.message : String(error)
}
