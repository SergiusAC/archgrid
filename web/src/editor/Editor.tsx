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
} from '@xyflow/react'
import { useCallback, useEffect, useMemo, useState } from 'react'

import type { Api } from '../api/client.ts'
import { ApiError } from '../api/client.ts'
import type { Command, ElementType, ModelElement, ModelRelationship } from '../api/types.ts'
import { Metamodel } from '../metamodel/Metamodel.ts'
import ArchimateNodeView, { type ArchimateNode } from './ArchimateNode.tsx'
import Palette from './Palette.tsx'
import { autoLayout, nextFreePosition } from './layout.ts'
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

  const positions = useLayoutStore((state) => state.positions)
  const setPositions = useLayoutStore((state) => state.setPositions)
  const setPosition = useLayoutStore((state) => state.setPosition)
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

  // Elements without a position have just appeared; lay the whole thing out again rather than
  // dropping them at the origin on top of each other.
  useEffect(() => {
    const missing = elements.filter((element) => !positions[element.key])
    if (missing.length === 0 || elements.length === 0) {
      return
    }
    if (Object.keys(positions).length === 0) {
      void autoLayout(elements, relationships).then(setPositions)
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
  }, [elements, relationships, positions, setPositions])

  const apply = useMutation({
    mutationFn: async ({ commands, label }: { commands: Command[]; label: string }) => {
      const inverse = invert(commands, { elements: byKey, relationships: relationshipsByKey })
      const result = await api.applyCommands(workspace, model, {
        baseCommitId: headCommitId,
        message: label,
        commands,
      })
      return { result, inverse, commands, label }
    },
    onSuccess: async ({ inverse, commands, label }) => {
      history.push({ forward: commands, inverse, label })
      setMessage(null)
      await queryClient.invalidateQueries({ queryKey: ['model', workspace, model] })
    },
    onError: (error: unknown) => setMessage({ kind: 'error', text: describe(error) }),
  })

  /** Applies a batch without recording it, used by undo and redo so they do not stack on themselves. */
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
      elements.map((element) => {
        const type = metamodel.elementType(element.conceptType)
        return {
          id: element.key,
          type: 'archimate' as const,
          position: positions[element.key] ?? { x: 0, y: 0 },
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
    [elements, positions, selected, metamodel, connectingFrom, byKey, relationshipType],
  )

  const edges: Edge[] = useMemo(
    () =>
      relationships.map((relationship) => ({
        id: relationship.key,
        source: relationship.source,
        target: relationship.target,
        label: relationship.name ?? relationship.type,
        className: `ag-edge ag-edge-${relationship.type.toLowerCase()}`,
        animated: relationship.type === 'Flow',
      })),
    [relationships],
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

  /** Called by React Flow while dragging; refusing here stops an invalid edge being dropped at all. */
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

  const deleteSelection = useCallback(() => {
    if (selected.length === 0) {
      return
    }
    const commands: Command[] = selected.map((key) => ({ op: 'delete-element', key }))
    const affected = relationships.filter(
      (relationship) => selected.includes(relationship.source) || selected.includes(relationship.target),
    )
    const names = selected.map((key) => byKey[key]?.name ?? key).join(', ')
    // "Delete from the model" is not "remove from a view", and the difference has to be visible
    // before the click, not after (ADR-0003).
    const confirmed = window.confirm(
      `Delete ${names} from the model?\n\n` +
        (affected.length > 0
          ? `${affected.length} relationship(s) will be deleted with it.\n\n`
          : 'No relationships reference it.\n\n') +
        'This changes the model itself, not just this diagram.',
    )
    if (confirmed) {
      apply.mutate({ commands, label: `Delete ${names}` })
    }
  }, [selected, relationships, byKey, apply])

  const undo = useCallback(async () => {
    const entry = history.popUndo()
    if (!entry) {
      return
    }
    const ok = await applySilently(entry.inverse, `Undo: ${entry.label}`)
    if (ok) {
      history.pushRedo(entry)
    }
  }, [history, applySilently])

  const redo = useCallback(async () => {
    const entry = history.popRedo()
    if (!entry) {
      return
    }
    const ok = await applySilently(entry.forward, `Redo: ${entry.label}`)
    if (ok) {
      history.push(entry)
    }
  }, [history, applySilently])

  const relayout = useCallback(() => {
    void autoLayout(elements, relationships).then(setPositions)
  }, [elements, relationships, setPositions])

  if (snapshot.isPending) {
    return <div className="ag-editor-loading">Loading model…</div>
  }
  if (snapshot.isError) {
    return <div className="ag-editor-loading error">{describe(snapshot.error)}</div>
  }

  return (
    <div className="ag-editor">
      <Palette
        metamodel={metamodel}
        viewpoint={viewpoint}
        onViewpointChange={setViewpoint}
        onCreate={(type: ElementType) => createElement(type.concept, type.name)}
        profiles={profiles}
        onCreateProfiled={(name) => createElement(name, name)}
        busy={apply.isPending}
      />

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
          <button type="button" onClick={() => void undo()} disabled={history.undoStack.length === 0}>
            Undo
          </button>
          <button type="button" onClick={() => void redo()} disabled={history.redoStack.length === 0}>
            Redo
          </button>
          <button type="button" className="ag-danger" onClick={deleteSelection} disabled={selected.length === 0}>
            Delete from model
          </button>
          <span className="ag-toolbar-spacer" />
          <span className="ag-muted">
            {elements.length} elements · {relationships.length} relationships
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
            fitView
            proOptions={{ hideAttribution: false }}
          >
            <Background gap={16} />
            <Controls />
            <MiniMap pannable zoomable />
          </ReactFlow>
        </div>
      </div>
    </div>
  )
}

/**
 * How a node should look while a connection is being dragged out of another one.
 *
 * This is the feature that makes the product something other than a drawing tool: the
 * metamodel answers "may this exist" before the user lets go of the mouse, so the notation is
 * taught by using it rather than by reading a manual.
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
